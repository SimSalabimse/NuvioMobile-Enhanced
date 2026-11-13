import { describe, expect, it } from "vitest";
import { collectFolder, segmentPath, snapshotPath } from "./reference/folder";

const spaceId = "11111111-1111-4111-8111-111111111111";
const header = {
  v: 1,
  spaceId,
  kdf: "pbkdf2-sha256",
  iterations: 600000,
  salt: "AAAAAAAAAAAAAAAAAAAAAA==",
  keyCheck: "AAAAAAAAAAAAAAAAAAAAAA==",
};
const blob = (id: string) => ({
  id,
  nonce: "AAAAAAAAAAAAAAAA",
  ciphertext: "AAAAAAAAAAAAAAAAAAAAAA==",
});

describe("folder layout", () => {
  it("builds device paths that cannot escape the folder", () => {
    expect(snapshotPath("iPhone.16")).toBe("devices/iPhone.16.json");
    expect(segmentPath("device-a", 1)).toBe("devices/device-a/0000000000000001.json");
    expect(() => snapshotPath("..")).toThrow("bad device id");
    expect(() => snapshotPath(".hidden")).toThrow("bad device id");
    expect(() => segmentPath("device-a", 0)).toThrow("bad segment seq");
  });

  it("lets a snapshot replace older segments and keeps a later one", () => {
    const files = new Map<string, unknown>([
      ["header.json", header],
      [".DS_Store", { ignored: true }],
      ["devices/device-b/0000000000000001.json", {
        v: 1, spaceId, deviceId: "device-b", seq: 1, blobs: [blob("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1")],
      }],
      ["devices/device-a.json", {
        v: 1, spaceId, deviceId: "device-a", throughSeq: 2,
        blobs: [blob("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1")],
      }],
      ["devices/device-a/0000000000000002.json", {
        v: 1, spaceId, deviceId: "device-a", seq: 2, blobs: [blob("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2")],
      }],
      ["devices/device-a/0000000000000003.json", {
        v: 1, spaceId, deviceId: "device-a", seq: 3, blobs: [blob("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3")],
      }],
    ]);
    const collected = collectFolder(files);
    expect(collected.header.spaceId).toBe(spaceId);
    expect(collected.blobs.map((item) => `${item.deviceId}:${item.id.slice(-1)}`)).toEqual([
      "device-a:1",
      "device-a:3",
      "device-b:1",
    ]);
  });

  it("rejects a segment whose seq disagrees with the file name", () => {
    const files = new Map<string, unknown>([
      ["header.json", header],
      ["devices/device-a/0000000000000004.json", {
        v: 1, spaceId, deviceId: "device-a", seq: 5, blobs: [],
      }],
    ]);
    expect(() => collectFolder(files)).toThrow("bad segment");
  });
});
