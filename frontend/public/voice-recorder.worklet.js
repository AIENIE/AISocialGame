class VoiceRecorderProcessor extends AudioWorkletProcessor {
  constructor({ processorOptions }) {
    super();
    this.samples = new Float32Array(Math.floor(sampleRate * processorOptions.maxSeconds));
    this.frames = 0;
    this.port.onmessage = ({ data }) => {
      if (data === "stop") this.finish();
    };
  }

  finish() {
    if (!this.samples) return;
    const samples = this.samples;
    this.samples = null;
    this.port.postMessage({ samples, frames: this.frames }, [samples.buffer]);
  }

  process(inputs) {
    if (!this.samples) return false;
    const mono = inputs[0]?.[0];
    if (mono) {
      const chunk = mono.subarray(0, this.samples.length - this.frames);
      this.samples.set(chunk, this.frames);
      this.frames += chunk.length;
      if (this.frames === this.samples.length) this.finish();
    }
    return this.samples !== null;
  }
}

registerProcessor("voice-recorder", VoiceRecorderProcessor);
