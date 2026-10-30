// Usage: node src/create-user.mjs <name> [custom-token]
// Without a custom token a random one is generated and printed once.
// A custom token must be at least 7 characters. Only its SHA-256 hash is stored.
import { randomBytes, createHash } from "node:crypto";
import { pool } from "./db.mjs";

const name = process.argv[2];
if (!name) {
  console.error("usage: create-user <name>");
  process.exit(1);
}
const custom = process.argv[3];
if (custom !== undefined && custom.length < 7) {
  console.error("custom token must be at least 7 characters");
  process.exit(1);
}
const token = custom ?? randomBytes(32).toString("base64url");
const hash = createHash("sha256").update(token).digest("hex");
await pool.query(
  "INSERT INTO users (name, token_hash, created_at) VALUES ($1, $2, $3)",
  [name, hash, Date.now()],
);
console.log(token);
await pool.end();
