importScripts('https://www.gstatic.com/firebasejs/9.0.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/9.0.0/firebase-messaging-compat.js');

const firebaseConfig = {
  apiKey: "AIzaSyAsx1mykpOquQItObJjbpnlhVC7lWvREes",
  authDomain: "lks-dialer.firebaseapp.com",
  databaseURL: "https://lks-dialer-default-rtdb.firebaseio.com",
  projectId: "lks-dialer",
  storageBucket: "lks-dialer.firebasestorage.app",
  messagingSenderId: "397514094733",
  appId: "1:397514094733:web:00d72ea19f9c4ba7bbcac4",
  measurementId: "G-QSY4MQS10E"
};

firebase.initializeApp(firebaseConfig);

const messaging = firebase.messaging();

// Instantly activate new service worker versions
self.addEventListener('install', (event) => {
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(clients.claim());
});

messaging.onBackgroundMessage((payload) => {
  console.log('[firebase-messaging-sw.js] Received background push message:', payload);

  const data = payload.data || {};
  const type = data.type;

  // 1. INCOMING CALL NOTIFICATION (Persistent, requireInteraction, vibration, Answer/Decline buttons)
  if (type === "incoming_call") {
    const callTypeStr = data.callType === 'VIDEO' ? 'Video' : 'Audio';
    const callerName = data.callerName || 'Unknown Caller';
    const title = `Incoming ${callTypeStr} Call`;
    
    let iconUrl = '/icon-192.png';
    if (data.callerProfilePic && (data.callerProfilePic.startsWith('http') || data.callerProfilePic.startsWith('data:image'))) {
      iconUrl = data.callerProfilePic;
    }

    const options = {
      body: `📞 ${callerName} is calling you...`,
      icon: iconUrl,
      badge: '/icon-192.png',
      tag: `call_${data.callId || 'active'}`,
      requireInteraction: true,
      renotify: true,
      vibrate: [500, 250, 500, 250, 500, 250, 500],
      actions: [
        { action: 'answer', title: '📞 Answer' },
        { action: 'decline', title: '❌ Decline' }
      ],
      data: {
        url: `/?callId=${encodeURIComponent(data.callId || '')}&callerName=${encodeURIComponent(callerName)}&callType=${data.callType || 'AUDIO'}`,
        callId: data.callId || '',
        callerName: callerName,
        callType: data.callType || 'AUDIO',
        callerNumber: data.callerNumber || '',
        type: 'incoming_call'
      }
    };

    return self.registration.showNotification(title, options);
  }

  // 2. CHAT MESSAGE NOTIFICATION (Sender name, text preview, Open Chat action)
  if (type === "chat_message") {
    const senderName = data.callerName || data.callerNumber || 'New Message';
    let previewText = data.messageText || data.messagePreview || '';

    if (!previewText) {
      if (data.mediaType === 'IMAGE') previewText = '📷 Photo';
      else if (data.mediaType === 'AUDIO') previewText = '🎤 Voice message';
      else if (data.mediaType === 'DOCUMENT') previewText = '📄 Document';
      else previewText = 'New message';
    }

    let iconUrl = '/icon-192.png';
    if (data.callerProfilePic && (data.callerProfilePic.startsWith('http') || data.callerProfilePic.startsWith('data:image'))) {
      iconUrl = data.callerProfilePic;
    }

    const options = {
      body: previewText,
      icon: iconUrl,
      badge: '/icon-192.png',
      tag: `chat_${data.callerNumber || 'peer'}`,
      renotify: true,
      vibrate: [200, 100, 200],
      actions: [
        { action: 'open_chat', title: '💬 Open Chat' }
      ],
      data: {
        url: `/?tab=chats&peer=${encodeURIComponent(data.callerNumber || '')}`,
        peerNumber: data.callerNumber || '',
        callerName: senderName,
        callerProfilePic: data.callerProfilePic || '',
        type: 'chat_message'
      }
    };

    return self.registration.showNotification(senderName, options);
  }

  // 3. MISSED CALL NOTIFICATION
  if (type === "missed_call") {
    // First dismiss ringing notification
    self.registration.getNotifications().then(notifications => {
      notifications.forEach(notification => {
        const nData = notification.data || {};
        if (nData.callId === data.callId || notification.tag === `call_${data.callId}` || notification.title.includes("Incoming")) {
          notification.close();
        }
      });
    });

    const callTypeStr = data.callType === 'VIDEO' ? 'Video' : 'Audio';
    const missedTitle = `Missed ${callTypeStr} Call`;
    let iconUrl = '/icon-192.png';
    if (data.callerProfilePic && (data.callerProfilePic.startsWith('http') || data.callerProfilePic.startsWith('data:image'))) {
      iconUrl = data.callerProfilePic;
    }

    const missedOptions = {
      body: `You missed a call from ${data.callerName || 'Unknown'}`,
      icon: iconUrl,
      badge: '/icon-192.png',
      tag: `missed_${data.callId || Date.now()}`,
      data: {
        url: `/?callerNumber=${encodeURIComponent(data.callerNumber || '')}`,
        type: 'missed_call'
      }
    };
    return self.registration.showNotification(missedTitle, missedOptions);
  }

  // 4. CANCEL CALL NOTIFICATION (Dismiss ringing notification)
  if (type === "cancel_call") {
    return self.registration.getNotifications().then(notifications => {
      notifications.forEach(notification => {
        const nData = notification.data || {};
        if (nData.callId === data.callId || notification.tag === `call_${data.callId}` || notification.title.includes("Incoming")) {
          notification.close();
        }
      });
    });
  }
});

self.addEventListener('notificationclick', function(event) {
  console.log('[firebase-messaging-sw.js] Notification click received. Action:', event.action);
  
  event.notification.close();
  const data = event.notification.data || {};
  const action = event.action;

  let targetUrl = data.url || '/';
  if (action === 'answer') {
    targetUrl += (targetUrl.includes('?') ? '&' : '?') + 'autoAnswer=true';
  } else if (action === 'decline') {
    targetUrl += (targetUrl.includes('?') ? '&' : '?') + 'autoDecline=true';
  }

  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windowClients) => {
      // Look for an existing open window/tab belonging to our app
      for (let i = 0; i < windowClients.length; i++) {
        const client = windowClients[i];
        if (client.url.includes(self.location.origin) || client.url.includes('lksdialerweb') || client.url.includes('localhost')) {
          client.focus();
          client.postMessage({
            type: 'NOTIFICATION_ACTION',
            action: action,
            data: data
          });
          return;
        }
      }
      // If no window is currently open, open a new one
      if (clients.openWindow) {
        return clients.openWindow(targetUrl);
      }
    })
  );
});
