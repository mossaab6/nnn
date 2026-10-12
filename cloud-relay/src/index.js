/**
 * Sham Cash Analyzer — Telegram relay (Cloudflare Worker + D1, free plan).
 *
 * Telegram sends every bot update to POST /telegram (webhook). The worker stores it in D1 until the
 * Android app pulls it with GET /pull and confirms with POST /ack. Telegram only keeps undelivered updates
 * for 24 hours, so this relay makes sure no PDF is lost even if the phone is offline for days.
 * Files themselves stay on Telegram's servers; only the small update JSON is stored here.
 *
 * Secrets / bindings (see README.md):
 *   RELAY_SECRET  (secret)  letters/digits, 16+ chars. Same value goes into the app settings.
 *   BOT_TOKEN     (secret, optional) lets the worker reply "received" instantly when a file arrives.
 *   DB            (D1 binding)
 */

const SCHEMA = `CREATE TABLE IF NOT EXISTS updates (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  update_id INTEGER UNIQUE,
  body TEXT NOT NULL,
  received_at INTEGER NOT NULL
)`;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function authorized(request, env) {
  const header = request.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  return !!env.RELAY_SECRET && timingSafeEqual(token, env.RELAY_SECRET);
}

async function ensureSchema(env) {
  await env.DB.prepare(SCHEMA).run();
}

async function quickReply(env, update) {
  const msg = update.message;
  if (!env.BOT_TOKEN || !msg || !(msg.document || msg.photo)) return;
  await fetch(`https://api.telegram.org/bot${env.BOT_TOKEN}/sendMessage`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      chat_id: msg.chat.id,
      text: "📥 تم استلام الملف، سيتم تحليله فور اتصال التطبيق.",
      reply_parameters: { message_id: msg.message_id, allow_sending_without_reply: true },
    }),
  });
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (!env.DB) return json({ error: "D1 binding 'DB' is missing" }, 500);
    if (!env.RELAY_SECRET) return json({ error: "RELAY_SECRET is not set" }, 500);

    // Telegram webhook
    if (request.method === "POST" && url.pathname === "/telegram") {
      const secret = request.headers.get("x-telegram-bot-api-secret-token") || "";
      if (!timingSafeEqual(secret, env.RELAY_SECRET)) return json({ error: "forbidden" }, 403);
      const body = await request.text();
      let update;
      try {
        update = JSON.parse(body);
      } catch {
        return json({ error: "bad json" }, 400);
      }
      if (typeof update.update_id !== "number") return json({ ok: true });
      await ensureSchema(env);
      await env.DB.prepare("INSERT OR IGNORE INTO updates (update_id, body, received_at) VALUES (?, ?, ?)")
        .bind(update.update_id, body, Date.now())
        .run();
      ctx.waitUntil(quickReply(env, update).catch(() => {}));
      return json({ ok: true });
    }

    if (url.pathname === "/" && request.method === "GET") {
      return new Response("Sham Cash Analyzer relay is running ✅", { headers: { "content-type": "text/plain; charset=utf-8" } });
    }

    // App endpoints (Bearer RELAY_SECRET)
    if (!authorized(request, env)) return json({ error: "unauthorized" }, 401);
    await ensureSchema(env);

    if (request.method === "GET" && url.pathname === "/pull") {
      const after = Number.parseInt(url.searchParams.get("after") || "0", 10) || 0;
      const limit = Math.min(Math.max(Number.parseInt(url.searchParams.get("limit") || "50", 10) || 50, 1), 100);
      const { results } = await env.DB.prepare("SELECT id, body FROM updates WHERE id > ? ORDER BY id LIMIT ?")
        .bind(after, limit)
        .all();
      return json({ updates: results.map((r) => ({ id: r.id, update: JSON.parse(r.body) })) });
    }

    if (request.method === "POST" && url.pathname === "/ack") {
      let upTo = 0;
      try {
        upTo = Number((await request.json()).upTo) || 0;
      } catch {
        return json({ error: "bad json" }, 400);
      }
      const res = await env.DB.prepare("DELETE FROM updates WHERE id <= ?").bind(upTo).run();
      return json({ ok: true, deleted: res.meta?.changes ?? 0 });
    }

    if (request.method === "GET" && url.pathname === "/health") {
      const row = await env.DB.prepare("SELECT COUNT(*) AS n FROM updates").first();
      return json({ ok: true, pending: row?.n ?? 0 });
    }

    return json({ error: "not found" }, 404);
  },
};
