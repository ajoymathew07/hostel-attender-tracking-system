/**
 * The {@code notification} module: the manager's live feed (SSE) and Web Push to attender phones.
 *
 * <p>Both send only after the change has committed, and both are best-effort: a lost message never
 * blocks or rolls back anything, because the screens remain the source of truth (sections 2.3, 9.5,
 * 9.6).
 */
package duty.notification;
