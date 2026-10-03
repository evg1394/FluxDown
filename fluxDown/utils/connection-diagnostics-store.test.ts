import { describe, expect, test } from "bun:test";
import {
  ConnectionDiagnosticsStore,
  DIAGNOSTICS_KEY,
  DIAGNOSTIC_MAX_AGE_MS,
  MAX_DIAGNOSTIC_ENTRIES,
  redactConnectionError,
} from "./connection-diagnostics-store";

class MemoryStorage {
  values: Record<string, unknown> = {};
  failNextWrite = false;
  async get(key: string): Promise<Record<string, unknown>> {
    await Promise.resolve();
    return { [key]: structuredClone(this.values[key]) };
  }
  async set(values: Record<string, unknown>): Promise<void> {
    if (this.failNextWrite) {
      this.failNextWrite = false;
      throw new Error("storage unavailable");
    }
    this.values = { ...this.values, ...structuredClone(values) };
  }
}

describe("connection diagnostic journal", () => {
  test("concurrent UI and background events survive a worker restart in order", async () => {
    const storage = new MemoryStorage();
    const store = new ConnectionDiagnosticsStore(storage, () => 100);
    await Promise.all([
      store.append({ event: "native.disconnected", detail: "host forbidden" }, "popup"),
      store.append({ event: "native.request_failed", action: "tasks", detail: "timeout" }, "background"),
      store.append({ event: "native.connected", action: "ping" }, "background"),
    ]);
    const restarted = new ConnectionDiagnosticsStore(storage, () => 101);
    expect((await restarted.snapshot()).map(({ event, source }) => [event, source])).toEqual([
      ["native.disconnected", "popup"],
      ["native.request_failed", "background"],
      ["native.connected", "background"],
    ]);
  });

  test("repeated errors preserve first/last occurrence and count without swallowing recovery", async () => {
    const storage = new MemoryStorage();
    let now = 100;
    const store = new ConnectionDiagnosticsStore(storage, () => now);
    const error = { event: "native.request_failed", action: "tasks", detail: "app_not_running" };
    await store.append(error, "background");
    now = 200;
    await store.append(error, "background");
    now = 300;
    await store.append({ event: "native.connected", action: "ping" }, "background");
    expect(await store.snapshot()).toEqual([
      { ...error, source: "background", firstAt: 100, lastAt: 200, count: 2 },
      { event: "native.connected", action: "ping", source: "background", firstAt: 300, lastAt: 300, count: 1 },
    ]);
  });

  test("retention drops old events and keeps the newest bounded history", async () => {
    const storage = new MemoryStorage();
    let now = 1;
    const store = new ConnectionDiagnosticsStore(storage, () => now);
    await store.append({ event: "native.connect_failed", detail: "old" }, "popup");
    now += DIAGNOSTIC_MAX_AGE_MS + 1;
    expect(await store.snapshot()).toEqual([]);
    for (let i = 0; i <= MAX_DIAGNOSTIC_ENTRIES; i++) {
      await store.append({ event: "native.request_failed", detail: `failure ${i}` }, "background");
    }
    const entries = await store.snapshot();
    expect(entries).toHaveLength(MAX_DIAGNOSTIC_ENTRIES);
    expect(entries[0].detail).toBe("failure 1");
    expect(entries.at(-1)?.detail).toBe(`failure ${MAX_DIAGNOSTIC_ENTRIES}`);
    expect(JSON.stringify(storage.values)).not.toContain('"old"');
  });

  test("redacts before persistence and never copies request fields into the journal", async () => {
    const storage = new MemoryStorage();
    const store = new ConnectionDiagnosticsStore(storage);
    await store.append({
      event: "native.disconnected", action: "https://private.test/task",
      detail: "Failed to start native messaging host. C:\\Users\\alice\\private-file.exe\nhttps://user:pass@private.test/task?token=secret\nCookie: sid=secret-cookie\nAuthorization: Bearer secret-token",
      cookies: "secret-cookie", headers: { Authorization: "secret-token" },
      url: "https://private.test/task", filename: "private-file", body: "private-body",
    }, "popup");
    const raw = JSON.stringify(storage.values);
    for (const secret of ["alice", "private-file", "private.test", "secret", "user:pass", "private-body"]) {
      expect(raw).not.toContain(secret);
    }
    const [entry] = await store.snapshot();
    expect(entry.event).toBe("native.disconnected");
    expect(entry.detail).toContain("Failed to start native messaging host.");
    expect(entry.action).toBeUndefined();
    expect(Object.keys(entry).sort()).toEqual(["count", "detail", "event", "firstAt", "lastAt", "source"]);
    expect(redactConnectionError("Host /home/alice/private-host\nBearer secret-token")).not.toContain("alice");
    expect(redactConnectionError("Host \\\\server\\secret-share\\host.exe")).not.toContain("secret-share");
  });

  test("a failed write is reported and does not poison later appends or export", async () => {
    const storage = new MemoryStorage();
    const store = new ConnectionDiagnosticsStore(storage);
    storage.failNextWrite = true;
    await expect(store.append({ event: "native.connect_failed" }, "popup")).rejects.toThrow("storage unavailable");
    await store.append({ event: "native.connected" }, "background");
    expect((await store.snapshot()).map((entry) => entry.event)).toEqual(["native.connected"]);
  });

  test("malformed persisted entries cannot leak arbitrary fields on export", async () => {
    const storage = new MemoryStorage();
    storage.values[DIAGNOSTICS_KEY] = [null, { event: "unknown", token: "secret" }, {
      event: "native.connected", source: "background", firstAt: 10, lastAt: 10, count: 1,
      token: "secret", url: "https://private.test",
    }];
    const store = new ConnectionDiagnosticsStore(storage, () => 20);
    expect(await store.snapshot()).toEqual([
      { event: "native.connected", source: "background", firstAt: 10, lastAt: 10, count: 1 },
    ]);
    await expect(store.append({ event: "unknown" }, "popup")).rejects.toThrow("Invalid diagnostic event");
  });
});
