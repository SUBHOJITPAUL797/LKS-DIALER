// LKS DIALER - Cloudflare Worker for FCM Push Notifications
// Sends incoming call push notifications via Firebase Cloud Messaging (FCM v1 API)
// Uses Service Account JWT auth — no Firebase billing required on the Android project.

export default {
  async fetch(request, env) {
    // Only allow POST requests
    if (request.method !== "POST") {
      return new Response("Method Not Allowed", { status: 405 });
    }

    // Validate shared secret to prevent abuse
    const authHeader = request.headers.get("X-Worker-Secret");
    if (!authHeader || authHeader !== env.WORKER_SECRET) {
      return new Response("Unauthorized", { status: 401 });
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return new Response("Invalid JSON body", { status: 400 });
    }

    const { token, callerName, callerNumber, callType, callId } = body;

    if (!token || !callId) {
      return new Response("Missing required fields: token, callId", { status: 400 });
    }

    try {
      // Step 1: Get OAuth2 access token from Google using Service Account
      const accessToken = await getGoogleAccessToken(
        env.FIREBASE_CLIENT_EMAIL,
        env.FIREBASE_PRIVATE_KEY
      );

      // Step 2: Send FCM push notification via v1 API
      const fcmUrl = `https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`;

      const fcmPayload = {
        message: {
          token: token,
          // Data-only payload so it works even when app is in background/killed
          data: {
            type: "incoming_call",
            callId: callId,
            callerName: callerName || "Unknown",
            callerNumber: callerNumber || "",
            callType: callType || "AUDIO",
          },
          android: {
            priority: "high",
            ttl: "30s", // Call notification expires after 30 seconds
          },
        },
      };

      const fcmResponse = await fetch(fcmUrl, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(fcmPayload),
      });

      const fcmResult = await fcmResponse.json();

      if (!fcmResponse.ok) {
        console.error("FCM Error:", JSON.stringify(fcmResult));
        return new Response(
          JSON.stringify({ success: false, error: fcmResult }),
          { status: 500, headers: { "Content-Type": "application/json" } }
        );
      }

      console.log("FCM notification sent. Message ID:", fcmResult.name);
      return new Response(
        JSON.stringify({ success: true, messageId: fcmResult.name }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );

    } catch (err) {
      console.error("Worker error:", err.message);
      return new Response(
        JSON.stringify({ success: false, error: err.message }),
        { status: 500, headers: { "Content-Type": "application/json" } }
      );
    }
  },
};

// ─────────────────────────────────────────────────────────────────────────────
// JWT + OAuth2 Helpers (using Web Crypto API — built into Cloudflare Workers)
// ─────────────────────────────────────────────────────────────────────────────

async function getGoogleAccessToken(clientEmail, privateKeyPem) {
  const jwt = await buildJWT(clientEmail, privateKeyPem);

  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });

  const tokenData = await tokenResponse.json();

  if (!tokenResponse.ok || !tokenData.access_token) {
    throw new Error(`OAuth token error: ${JSON.stringify(tokenData)}`);
  }

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

  // Import the private key
  const privateKey = await importPrivateKey(privateKeyPem);

  // Sign with RS256
  const signature = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5" },
    privateKey,
    new TextEncoder().encode(signingInput)
  );

  const encodedSignature = base64urlFromBuffer(signature);
  return `${signingInput}.${encodedSignature}`;
}

async function importPrivateKey(pem) {
  // Strip PEM headers and whitespace
  const pemContents = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s+/g, "");

  const binaryDer = Uint8Array.from(atob(pemContents), (c) => c.charCodeAt(0));

  return crypto.subtle.importKey(
    "pkcs8",
    binaryDer.buffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
}

function base64url(str) {
  return btoa(unescape(encodeURIComponent(str)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=/g, "");
}

function base64urlFromBuffer(buffer) {
  return btoa(String.fromCharCode(...new Uint8Array(buffer)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=/g, "");
}
