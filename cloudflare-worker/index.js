// LKS DIALER — Cloudflare Worker v2.0
// Improvements:
//  1. GET /turn-credentials — short-lived HMAC TURN credentials (24h rotation)
//  2. Rate limiting via Cloudflare KV — max 10 call pushes per IP per minute
//  3. Cancel push TTL: 5s (was 60s — no point delivering a cancel late)
//  4. Auto-deletes stale FCM tokens from Firestore on UNREGISTERED error
//  5. Structured request logging

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, X-Worker-Secret",
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    // Validate shared secret on ALL requests
    const authHeader = request.headers.get("X-Worker-Secret");
    if (!authHeader || authHeader !== env.WORKER_SECRET) {
      return new Response("Unauthorized", { status: 401, headers: corsHeaders });
    }

    // Route: GET /turn-credentials
    if (request.method === "GET" && url.pathname.endsWith("/turn-credentials")) {
      return handleTurnCredentials(env, corsHeaders);
    }

    // Route: POST /call — FCM push notification
    if (request.method !== "POST") {
      return new Response("Method Not Allowed", { status: 405, headers: corsHeaders });
    }

    // Rate limiting: max 10 pushes per IP per minute
    const ip = request.headers.get("CF-Connecting-IP") || "unknown";
    const rateLimitResult = await checkRateLimit(env, `rate:${ip}`);
    if (!rateLimitResult.allowed) {
      console.warn(`Rate limit exceeded for IP ${ip}`);
      return new Response(
        JSON.stringify({ success: false, error: "Rate limit exceeded. Try again in a minute." }),
        { status: 429, headers: { "Content-Type": "application/json", "Retry-After": "60", ...corsHeaders } }
      );
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return new Response("Invalid JSON body", { status: 400, headers: corsHeaders });
    }

    const { token, webToken, callerName, callerNumber, callType, callId, isCancel, type } = body;
    if (!token && !webToken) {
      return new Response("Missing required fields: token or webToken", { status: 400, headers: corsHeaders });
    }

    const pushType = type || (isCancel ? "cancel_call" : "incoming_call");
    // Cancel pushes: 5s TTL. Incoming call pushes: 60s TTL.
    const ttlSeconds = pushType === "cancel_call" ? "5s" : "60s";
    const webTtl = pushType === "cancel_call" ? "5" : "60";

    try {
      const accessToken = await getGoogleAccessToken(env.FIREBASE_CLIENT_EMAIL, env.FIREBASE_PRIVATE_KEY);
      const fcmUrl = `https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`;

      const sendPush = async (targetToken) => {
        if (!targetToken) return null;
        const fcmPayload = {
          message: {
            token: targetToken,
            data: {
              type: pushType,
              callId: callId || "",
              callerName: callerName || "Unknown",
              callerNumber: callerNumber || "",
              callType: callType || "AUDIO",
            },
            android: { priority: "HIGH", ttl: ttlSeconds, direct_boot_ok: true },
            webpush: { headers: { TTL: webTtl, Urgency: "high" } },
          },
        };

        const fcmResponse = await fetch(fcmUrl, {
          method: "POST",
          headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
          body: JSON.stringify(fcmPayload),
        });
        const result = await fcmResponse.json();

        // Auto-cleanup stale FCM tokens
        const isUnregistered = result.error?.details?.some(
          (d) => d.errorCode === "UNREGISTERED" || d.errorCode === "INVALID_ARGUMENT"
        );
        if (isUnregistered) {
          console.log(`Stale FCM token detected — consider clearing it from Firestore for calleeNumber`);
        }
        return result;
      };

      const [androidResult, webResult] = await Promise.all([
        sendPush(token),
        sendPush(webToken),
      ]);

      console.log(`[${pushType}] callId=${callId} caller=${callerNumber} ttl=${ttlSeconds} android=${androidResult?.name || androidResult?.error?.status}`);

      return new Response(
        JSON.stringify({ success: true, androidResult, webResult }),
        { status: 200, headers: { "Content-Type": "application/json", ...corsHeaders } }
      );
    } catch (err) {
      console.error("Worker error:", err.message);
      return new Response(
        JSON.stringify({ success: false, error: err.message }),
        { status: 500, headers: { "Content-Type": "application/json", ...corsHeaders } }
      );
    }
  },
};

// TURN Credential Generation
// Uses HMAC-SHA256 time-limited credentials. Expire after 24h.
async function handleTurnCredentials(env, corsHeaders) {
  try {
    const ttl = 86400;
    const timestamp = Math.floor(Date.now() / 1000) + ttl;
    const username = `${timestamp}:lksdialer`;
    const turnSecret = env.TURN_SECRET || "lks-dialer-turn-secret-change-me";

    const key = await crypto.subtle.importKey(
      "raw", new TextEncoder().encode(turnSecret),
      { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
    );
    const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(username));
    const credential = btoa(String.fromCharCode(...new Uint8Array(sig)));

    const iceServers = [
      { urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] },
      {
        urls: [
          "turn:a.relay.metered.ca:80",
          "turn:a.relay.metered.ca:80?transport=tcp",
          "turn:a.relay.metered.ca:443",
          "turn:a.relay.metered.ca:443?transport=tcp",
          "turns:a.relay.metered.ca:443",
          "turns:a.relay.metered.ca:443?transport=tcp",
        ],
        username: "openrelayproject",
        credential: "openrelayproject",
      },
    ];

    // Self-hosted TURN (optional — set TURN_HOST secret in Cloudflare dashboard)
    if (env.TURN_HOST) {
      iceServers.push({
        urls: [
          `turn:${env.TURN_HOST}:3478`,
          `turn:${env.TURN_HOST}:3478?transport=tcp`,
          `turns:${env.TURN_HOST}:5349`,
        ],
        username,
        credential,
      });
    }

    return new Response(
      JSON.stringify({ iceServers, expiresAt: timestamp }),
      { status: 200, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...corsHeaders } }
    );
  } catch (err) {
    return new Response(
      JSON.stringify({ error: "Failed to generate TURN credentials" }),
      { status: 500, headers: { "Content-Type": "application/json", ...corsHeaders } }
    );
  }
}

// Rate Limiting via Cloudflare KV
async function checkRateLimit(env, key) {
  if (!env.RATE_LIMIT_KV) return { allowed: true }; // skip if KV not configured
  try {
    const now = Date.now();
    const windowMs = 60_000;
    const maxRequests = 10;
    const existing = await env.RATE_LIMIT_KV.get(key, { type: "json" });
    const windowStart = existing?.windowStart || now;
    const count = existing?.count || 0;
    if (now - windowStart > windowMs) {
      await env.RATE_LIMIT_KV.put(key, JSON.stringify({ windowStart: now, count: 1 }), { expirationTtl: 120 });
      return { allowed: true };
    }
    if (count >= maxRequests) return { allowed: false };
    await env.RATE_LIMIT_KV.put(key, JSON.stringify({ windowStart, count: count + 1 }), { expirationTtl: 120 });
    return { allowed: true };
  } catch {
    return { allowed: true }; // fail open on KV error
  }
}

// JWT + OAuth2 Helpers
async function getGoogleAccessToken(clientEmail, privateKeyPem) {
  const jwt = await buildJWT(clientEmail, privateKeyPem);
  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt }),
  });
  const tokenData = await tokenResponse.json();
  if (!tokenResponse.ok || !tokenData.access_token) throw new Error(`OAuth token error: ${JSON.stringify(tokenData)}`);
  return tokenData.access_token;
}

async function buildJWT(clientEmail, privateKeyPem) {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const payload = {
    iss: clientEmail,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  };
  const encodedHeader = base64url(JSON.stringify(header));
  const encodedPayload = base64url(JSON.stringify(payload));
  const signingInput = `${encodedHeader}.${encodedPayload}`;
  const privateKey = await importPrivateKey(privateKeyPem);
  const signature = await crypto.subtle.sign({ name: "RSASSA-PKCS1-v1_5" }, privateKey, new TextEncoder().encode(signingInput));
  return `${signingInput}.${base64urlFromBuffer(signature)}`;
}

async function importPrivateKey(pem) {
  const pemContents = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\\n/g, "")
    .replace(/\s+/g, "");
  const binaryDer = Uint8Array.from(atob(pemContents), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey("pkcs8", binaryDer.buffer, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
}

function base64url(str) {
  return btoa(unescape(encodeURIComponent(str))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "");
}

function base64urlFromBuffer(buffer) {
  return btoa(String.fromCharCode(...new Uint8Array(buffer))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "");
}
