# LKS DIALER — Cloudflare Worker Deployment Guide

## What This Worker Does
This Cloudflare Worker is the **push notification relay** for LKS DIALER.
When Person A calls Person B, the Android app hits this Worker, which then sends
an FCM push to Person B's phone — waking it up even if the app is closed.

---

## Step 1 — Get Firebase Service Account Credentials

You need a Firebase Service Account key to let the Worker talk to FCM.

1. Go to [Firebase Console](https://console.firebase.google.com) → **Project: lks-dialer**
2. Click the ⚙️ gear → **Project Settings** → **Service accounts** tab
3. Click **"Generate new private key"** → Download the JSON file
4. Open the downloaded JSON. You need these 3 values:
   - `project_id` → e.g. `lks-dialer`
   - `client_email` → e.g. `firebase-adminsdk-xxxx@lks-dialer.iam.gserviceaccount.com`
   - `private_key` → the long RSA key starting with `-----BEGIN PRIVATE KEY-----`

---

## Step 2 — Install Wrangler CLI

```bash
npm install -g wrangler
wrangler login
```

---

## Step 3 — Deploy the Worker

```bash
cd cloudflare-worker
wrangler deploy
```

You'll get a URL like: `https://lks-dialer-call-notifier.<your-subdomain>.workers.dev`

---

## Step 4 — Set Secrets (run each command, paste value when prompted)

```bash
wrangler secret put FIREBASE_PROJECT_ID
# paste: lks-dialer

wrangler secret put FIREBASE_CLIENT_EMAIL
# paste: firebase-adminsdk-xxxx@lks-dialer.iam.gserviceaccount.com

wrangler secret put FIREBASE_PRIVATE_KEY
# paste: -----BEGIN PRIVATE KEY-----\nMIIE...your full key...\n-----END PRIVATE KEY-----\n

wrangler secret put WORKER_SECRET
# paste: any random strong password, e.g. LKS_DIALER_SECRET_2024
```

> ⚠️ The `FIREBASE_PRIVATE_KEY` must be pasted as a single line with `\n` for newlines,
> exactly as it appears in the downloaded JSON file.

---

## Step 5 — Configure the Android App

Open `.env` in the project root (copy from `.env.example`) and set:

```
CALL_WORKER_URL=https://lks-dialer-call-notifier.<your-subdomain>.workers.dev/call
CALL_WORKER_SECRET=LKS_DIALER_SECRET_2024
```

Then rebuild the app. Done!

---

## Testing the Worker

Use `curl` or any REST client to test:

```bash
curl -X POST https://lks-dialer-call-notifier.<your>.workers.dev/call \
  -H "Content-Type: application/json" \
  -H "X-Worker-Secret: LKS_DIALER_SECRET_2024" \
  -d '{
    "token": "FCM_DEVICE_TOKEN_HERE",
    "callerName": "Test Caller",
    "callerNumber": "+919999999999",
    "callType": "AUDIO",
    "callId": "test-call-id-123"
  }'
```

Expected response: `{"success":true,"messageId":"projects/lks-dialer/messages/..."}`

---

## How It All Works Together

```
Person A taps "Call"
    ↓
Android app writes /calls/{callId} to Firestore
    ↓
Android app POSTs to Cloudflare Worker with Person B's FCM token
    ↓
Worker generates Google OAuth2 token using Service Account
    ↓
Worker calls FCM v1 API → sends push to Person B's phone
    ↓
Person B's phone rings (even if app is closed!)
    ↓
Person B opens app → sees incoming call screen
    ↓
Both connect via Firestore signaling (WebRTC state machine)
```
