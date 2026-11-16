const MAX_SECONDS = 30;
const WAV_SAMPLE_RATE = 16_000;
const WAV_HEADER_BYTES = 44;

export function supportsVoiceRecording() {
  return window.isSecureContext && typeof navigator.mediaDevices?.getUserMedia === "function"
    && typeof AudioContext === "function" && "audioWorklet" in AudioContext.prototype
    && typeof AudioWorkletNode === "function" && typeof OfflineAudioContext === "function";
}

export async function recordVoice(signal: AbortSignal, onReady: (stop: () => void) => void): Promise<Blob | null> {
  signal.throwIfAborted();
  const context = new AudioContext();
  let stream: MediaStream | undefined;
  let source: MediaStreamAudioSourceNode | undefined;
  let recorder: AudioWorkletNode | undefined;
  let recordingTimer: number | undefined;
  let stopTimer: number | undefined;
  let released = false;
  const release = () => {
    if (released) return;
    released = true;
    window.clearTimeout(recordingTimer);
    window.clearTimeout(stopTimer);
    stream?.getTracks().forEach(track => track.stop());
    source?.disconnect();
    recorder?.disconnect();
    recorder?.port.close();
    if (context.state !== "closed") void context.close().catch(() => undefined);
  };
  let rejectAborted: (reason: unknown) => void;
  const aborted = new Promise<never>((_, reject) => { rejectAborted = reject; });
  const abort = () => {
    release();
    rejectAborted(signal.reason);
  };
  signal.addEventListener("abort", abort, { once: true });

  try {
    await Promise.race([
      Promise.all([
        context.resume(),
        context.audioWorklet.addModule(`${import.meta.env.BASE_URL}voice-recorder.worklet.js`),
        navigator.mediaDevices.getUserMedia({ audio: { channelCount: { ideal: 1 } } }).then(capture => {
          if (released) capture.getTracks().forEach(track => track.stop());
          else stream = capture;
        }),
      ]),
      aborted,
    ]);
    signal.throwIfAborted();
    source = context.createMediaStreamSource(stream);
    recorder = new AudioWorkletNode(context, "voice-recorder", {
      channelCount: 1,
      channelCountMode: "explicit",
      outputChannelCount: [1],
      processorOptions: { maxSeconds: MAX_SECONDS },
    });
    let stop: () => void;
    const recorded = new Promise<Float32Array>((resolve, reject) => {
      const fail = () => reject(new Error("Microphone capture ended"));
      const tracks = stream.getAudioTracks();
      tracks.forEach(track => track.addEventListener("ended", fail, { once: true, signal }));
      if (!tracks.some(track => track.readyState === "live")) fail();
      recorder.onprocessorerror = fail;
      recorder.port.onmessage = ({ data }: MessageEvent<{ samples: Float32Array; frames: number }>) =>
        resolve(data.samples.subarray(0, data.frames));
      stop = () => {
        if (stopTimer !== undefined || released) return;
        recorder.port.postMessage("stop");
        tracks.forEach(track => track.stop());
        stopTimer = window.setTimeout(fail, 1_000);
      };
    });
    source.connect(recorder).connect(context.destination);
    recordingTimer = window.setTimeout(stop, MAX_SECONDS * 1_000);
    onReady(stop);
    const samples = await Promise.race([recorded, aborted]);
    release();
    if (!samples.length) return null;
    const wav = await encodeVoiceWav(samples, context.sampleRate);
    signal.throwIfAborted();
    return wav;
  } finally {
    signal.removeEventListener("abort", abort);
    release();
  }
}

export async function encodeVoiceWav(samples: Float32Array, sampleRate: number): Promise<Blob> {
  const frames = Math.min(MAX_SECONDS * WAV_SAMPLE_RATE, Math.ceil(samples.length * WAV_SAMPLE_RATE / sampleRate));
  let mono = samples;
  if (sampleRate !== WAV_SAMPLE_RATE) {
    const resampler = new OfflineAudioContext(1, frames, WAV_SAMPLE_RATE);
    const buffer = resampler.createBuffer(1, samples.length, sampleRate);
    buffer.getChannelData(0).set(samples);
    const source = resampler.createBufferSource();
    source.buffer = buffer;
    source.connect(resampler.destination);
    source.start();
    mono = (await resampler.startRendering()).getChannelData(0);
  }

  const bytes = frames * 2;
  const wav = new ArrayBuffer(WAV_HEADER_BYTES + bytes);
  const view = new DataView(wav);
  const tag = (offset: number, value: string) => {
    for (let index = 0; index < value.length; index++) view.setUint8(offset + index, value.charCodeAt(index));
  };
  tag(0, "RIFF");
  view.setUint32(4, wav.byteLength - 8, true);
  tag(8, "WAVE");
  tag(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, WAV_SAMPLE_RATE, true);
  view.setUint32(28, WAV_SAMPLE_RATE * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  tag(36, "data");
  view.setUint32(40, bytes, true);
  for (let index = 0; index < frames; index++) {
    const sample = Math.max(-1, Math.min(1, mono[index]));
    view.setInt16(WAV_HEADER_BYTES + index * 2, Math.round(sample * (sample < 0 ? 32768 : 32767)), true);
  }
  return new Blob([wav], { type: "audio/wav" });
}
