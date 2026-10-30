import { readFile } from "node:fs/promises";
import { pool } from "./db.mjs";

const sql = await readFile(new URL("../schema.sql", import.meta.url), "utf8");
await pool.query(sql);
console.log("schema applied");
await pool.end();
