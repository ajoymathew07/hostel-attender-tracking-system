// The service worker of section 9.6: shows a push alert, and opens the task when it is tapped.
//
// Served from the site root so its scope covers /m and everything under it. The payload is the one
// PushNotifications builds: title, body, tag and url, with no student detail in it.
self.addEventListener('push', function (event) {
  var message = event.data ? event.data.json() : {};
  event.waitUntil(
    self.registration.showNotification(message.title || 'Hostel Duty Tracker', {
      body: message.body,
      // The same tag replaces the earlier alert, so an escalation does not stack on the assignment.
      tag: message.tag,
      data: { url: message.url || '/m' }
    })
  );
});

self.addEventListener('notificationclick', function (event) {
  event.notification.close();
  event.waitUntil(self.clients.openWindow(event.notification.data.url));
});
