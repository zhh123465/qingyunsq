self.addEventListener('install', (event) => {
  event.waitUntil(self.skipWaiting());
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      if ('caches' in self) {
        const keys = await caches.keys();
        await Promise.all(keys.map((key) => caches.delete(key)));
      }

      const registrations = await self.registration.unregister();
      await self.clients.claim();

      const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      clients.forEach((client) => {
        if ('navigate' in client) {
          client.navigate(client.url);
        } else {
          client.postMessage({ type: 'SW_CLEANED', unregistered: registrations });
        }
      });
    })(),
  );
});
