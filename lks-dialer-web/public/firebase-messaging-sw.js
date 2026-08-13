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

messaging.onBackgroundMessage((payload) => {
  console.log('[firebase-messaging-sw.js] Received background message ', payload);

  const notificationTitle = `Incoming ${payload.data?.callType === 'VIDEO' ? 'Video' : 'Audio'} Call`;
  const notificationOptions = {
    body: `Call from ${payload.data?.callerName || 'Unknown'}`,
    icon: '/icon-192.png',
    data: {
      url: '/', // When clicked, focus or open the main app
    }
  };

  // Only show notification if it's an incoming_call
  if (payload.data?.type === "incoming_call") {
    return self.registration.showNotification(notificationTitle, notificationOptions);
  }
});

self.addEventListener('notificationclick', function(event) {
  console.log('[firebase-messaging-sw.js] Notification click received.');
  
  event.notification.close();
  
  // This looks to see if the current window is already open and focuses if it is
  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windowClients) => {
      // Check if there is already a window/tab open with the target URL
      for (var i = 0; i < windowClients.length; i++) {
        var client = windowClients[i];
        if (client.url === event.notification.data.url || client.url.includes('localhost:5173')) {
          return client.focus();
        }
      }
      // If not, open a new window
      return clients.openWindow(event.notification.data.url);
    })
  );
});
