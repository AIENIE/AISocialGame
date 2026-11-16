// @vitest-environment node
import { expect, it } from "vitest";
import { encodeVoiceWav } from "./voiceRecording";

it("encodes the canonical mono PCM header and clamps samples to signed PCM16", async () => {
  const audio = await encodeVoiceWav(new Float32Array([-2, -0.5, 0.5, 2]), 16_000);
  const view = new DataView(await audio.arrayBuffer());
  expect(audio.type).toBe("audio/wav");
  expect(audio.size).toBe(52);
  expect(new TextDecoder().decode(await audio.slice(0, 4).arrayBuffer())).toBe("RIFF");
  expect(view.getUint32(4, true)).toBe(44);
  expect(view.getUint16(20, true)).toBe(1);
  expect(view.getUint16(22, true)).toBe(1);
  expect(view.getUint32(24, true)).toBe(16_000);
  expect(view.getUint16(34, true)).toBe(16);
  expect(view.getUint32(40, true)).toBe(8);
  expect([44, 46, 48, 50].map(offset => view.getInt16(offset, true))).toEqual([-32768, -16384, 16384, 32767]);
});

it("caps output at 30 seconds", async () => {
  const audio = await encodeVoiceWav(new Float32Array(31 * 16_000), 16_000);
  expect(audio.size).toBe(960_044);
});
