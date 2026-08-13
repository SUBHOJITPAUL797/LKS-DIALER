import { initializeApp } from "firebase/app";
import { getFirestore } from "firebase/firestore";
import { getMessaging } from "firebase/messaging";

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

const app = initializeApp(firebaseConfig);
export const db = getFirestore(app);
export const messaging = typeof window !== 'undefined' && 'serviceWorker' in navigator ? getMessaging(app) : null;

