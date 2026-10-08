(() => {
  const reloadFlag = 'xiaoqing-sw-clean-reloaded';
  const getRedirectTarget = () => {
    const pathname = window.location.pathname.replace(/\/+$/, '') || '/';
    const legacyRedirects = {
      '/square': '/resources',
      '/spaces': '/learning',
      '/checkin': '/learning',
    };

    if (legacyRedirects[pathname]) {
      return legacyRedirects[pathname];
    }

    const token = localStorage.getItem('token');
    const role = localStorage.getItem('role');
    if (pathname === '/' && token && role !== 'GUEST') {
      return '/resources';
    }

    return '';
  };

  const clearCaches = async () => {
    if (!('caches' in window)) return;
    const keys = await window.caches.keys();
    await Promise.all(keys.map((key) => window.caches.delete(key)));
  };

  const unregisterServiceWorkers = async () => {
    if (!('serviceWorker' in navigator)) return;
    const registrations = await navigator.serviceWorker.getRegistrations();
    await Promise.all(registrations.map((registration) => registration.unregister()));
  };

  Promise.allSettled([clearCaches(), unregisterServiceWorkers()]).then(() => {
    const redirectTarget = getRedirectTarget();
    if (redirectTarget) {
      window.location.replace(redirectTarget);
      return;
    }

    if (navigator.serviceWorker?.controller && sessionStorage.getItem(reloadFlag) !== '1') {
      sessionStorage.setItem(reloadFlag, '1');
      window.location.reload();
    } else if (!navigator.serviceWorker?.controller) {
      sessionStorage.removeItem(reloadFlag);
    }
  });
})();
