// Usage: node src/create-user.mjs <name>
// Prints a one-time token. Only its SHA-256 hash is stored.
import { randomBytes, createHash } from "node:crypto";
import { pool } from "./db.mjs";

const name = process.argv[2];
if (!name) {
  console.error("usage: create-user <name>");
  process.exit(1);
}
const token = randomBytes(32).toString("base64url");
const hash = createHash("sha256").update(token).digest("hex");
await pool.query(
  "INSERT INTO users (name, token_hash, created_at) VALUES ($1, $2, $3)",
  [name, hash, Date.now()],
);
console.log(token);
await pool.end();
