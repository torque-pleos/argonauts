// 크루 소식 알림 전용 서비스 워커. 휴대폰 브라우저(특히 Android Chrome)는 페이지에서 바로 new Notification()을 못 쓰고
// 서비스 워커의 showNotification()만 허용한다. 알림을 누르면 열려 있는 크루 화면으로 돌아간다.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (wins.length) return wins[0].focus();
    return self.clients.openWindow(e.notification.data?.url || './');
  })());
});
