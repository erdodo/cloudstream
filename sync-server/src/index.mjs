// Sync protocol (all under /v1, JSON, Authorization: Bearer <token>)
//   GET  /v1/changes?since=<ms>   -> { now, entries: [{key,value,deleted,updatedAt,deviceId}] }
//   POST /v1/changes              -> body { deviceId, entries:[{key,value,deleted,updatedAt}] }
//                                    -> { accepted, rejected } (LWW: stored only if newer)
import { createServer } from "node:http";
import { createHash } from "node:crypto";
import { pool } from "./db.mjs";

const MAX_BODY = 5 * 1024 * 1024; // 5 MB per request
const MAX_ENTRIES = 2000;
const PORT = Number(process.env.PORT ?? 8787);

function send(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    "content-type": "application/json",
    "content-length": Buffer.byteLength(body),
  });
  res.end(body);
}

async function readJson(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY) throw Object.assign(new Error("body too large"), { status: 413 });
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
  } catch {
    throw Object.assign(new Error("invalid json"), { status: 400 });
  }
}

async function authenticate(req) {
  const header = req.headers.authorization ?? "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!token) return null;
  const hash = createHash("sha256").update(token).digest("hex");
  const { rows } = await pool.query("SELECT id FROM users WHERE token_hash = $1", [hash]);
  if (rows.length === 0) return null;
  return rows[0].id;
}

async function handleGet(userId, url, res) {
  const since = Number(url.searchParams.get("since") ?? 0);
  if (!Number.isFinite(since) || since < 0) return send(res, 400, { error: "bad since" });
  const now = Date.now();
  const { rows } = await pool.query(
    `SELECT key, value, deleted, updated_at, device_id FROM sync_entries
     WHERE user_id = $1 AND updated_at > $2 ORDER BY updated_at ASC LIMIT $3`,
    [userId, since, MAX_ENTRIES],
  );
  send(res, 200, {
    now,
    more: rows.length === MAX_ENTRIES,
    entries: rows.map((r) => ({
      key: r.key,
      value: r.value,
      deleted: r.deleted,
      updatedAt: Number(r.updated_at),
      deviceId: r.device_id,
    })),
  });
}

function validEntry(e) {
  return (
    e &&
    typeof e.key === "string" && e.key.length > 0 && e.key.length <= 512 &&
    (e.value === null || typeof e.value === "string") &&
    typeof e.updatedAt === "number" && Number.isSafeInteger(e.updatedAt) && e.updatedAt > 0 &&
    typeof e.deleted === "boolean"
  );
}

async function handlePost(userId, req, res) {
  const body = await readJson(req);
  if (typeof body.deviceId !== "string" || !Array.isArray(body.entries)) {
    return send(res, 400, { error: "deviceId and entries required" });
  }
  if (body.entries.length > MAX_ENTRIES) return send(res, 413, { error: "too many entries" });
  const valid = body.entries.filter(validEntry);
  const rejected = body.entries.length - valid.length;
  if (valid.length === 0) return send(res, 200, { accepted: 0, rejected });

  // One statement: insert all, keep the newer row on conflict.
  const keys = valid.map((e) => e.key);
  const values = valid.map((e) => (e.deleted ? null : e.value));
  const deleted = valid.map((e) => e.deleted);
  const times = valid.map((e) => e.updatedAt);
  const result = await pool.query(
    `INSERT INTO sync_entries (user_id, key, value, deleted, updated_at, device_id)
     SELECT $1, t.key, t.value, t.deleted, t.updated_at, $6
     FROM unnest($2::text[], $3::text[], $4::bool[], $5::bigint[])
       AS t(key, value, deleted, updated_at)
     ON CONFLICT (user_id, key) DO UPDATE SET
       value = EXCLUDED.value,
       deleted = EXCLUDED.deleted,
       updated_at = EXCLUDED.updated_at,
       device_id = EXCLUDED.device_id
     WHERE sync_entries.updated_at < EXCLUDED.updated_at`,
    [userId, keys, values, deleted, times, body.deviceId],
  );
  send(res, 200, { accepted: result.rowCount, rejected });
}

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url ?? "/", "http://localhost");
    if (url.pathname === "/healthz") return send(res, 200, { ok: true });
    if (url.pathname !== "/v1/changes") return send(res, 404, { error: "not found" });

    const userId = await authenticate(req);
    if (userId === null) return send(res, 401, { error: "unauthorized" });

    if (req.method === "GET") return await handleGet(userId, url, res);
    if (req.method === "POST") return await handlePost(userId, req, res);
    return send(res, 405, { error: "method not allowed" });
  } catch (err) {
    if (err.status) return send(res, err.status, { error: err.message });
    console.error(err);
    return send(res, 500, { error: "internal" });
  }
});

server.listen(PORT, () => console.log(`sync server on :${PORT}`));
