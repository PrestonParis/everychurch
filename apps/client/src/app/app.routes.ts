import { Route } from '@angular/router';
// The directory is the home page, so it stays in the main bundle for a fast first paint.
// Everything else loads on demand — which also keeps the QR-code and STOMP libraries (each
// used by a single page) out of the initial download.
import { ChurchListComponent } from './pages/church-list/church-list.component';

export const appRoutes: Route[] = [
  { path: 'admin/faith', loadComponent: () => import('./pages/learn/admin-faith.component').then(m => m.AdminFaithComponent) },
  { path: 'learn', loadComponent: () => import('./pages/learn/learn.component').then(m => m.LearnComponent) },
  { path: 'learn/traditions/:slug', loadComponent: () => import('./pages/learn/learn.component').then(m => m.LearnComponent) },
  { path: 'learn/denomination-quiz', loadComponent: () => import('./pages/learn/quiz.component').then(m => m.QuizComponent) },
  { path: 'learn/denomination-quiz/results', loadComponent: () => import('./pages/learn/results.component').then(m => m.ResultsComponent) },
  { path: 'churches/:id/beliefs/edit', loadComponent: () => import('./pages/learn/beliefs.component').then(m => m.BeliefsComponent), data: { editing: true } },
  { path: 'churches/:id/beliefs', loadComponent: () => import('./pages/learn/beliefs.component').then(m => m.BeliefsComponent) },
  { path: '', redirectTo: '/churches', pathMatch: 'full' },
  { path: 'churches', component: ChurchListComponent },
  { path: 'account', loadComponent: () => import('./pages/account/account.component').then((m) => m.AccountComponent) },
  { path: 'favorites', loadComponent: () => import('./pages/favorites/favorites.component').then((m) => m.FavoritesComponent) },
  { path: 'feed', loadComponent: () => import('./pages/feed/feed.component').then((m) => m.FeedComponent) },
  { path: 'messages', loadComponent: () => import('./pages/messages/messages.component').then((m) => m.MessagesComponent) },
  { path: 'messages/:userId', loadComponent: () => import('./pages/messages/message-thread.component').then((m) => m.MessageThreadComponent) },
  { path: 'login', loadComponent: () => import('./pages/auth/auth.component').then((m) => m.AuthComponent), data: { mode: 'login' } },
  { path: 'register', loadComponent: () => import('./pages/auth/auth.component').then((m) => m.AuthComponent), data: { mode: 'register' } },
  { path: 'forgot-password', loadComponent: () => import('./pages/forgot-password/forgot-password.component').then((m) => m.ForgotPasswordComponent) },
  { path: 'reset-password', loadComponent: () => import('./pages/reset-password/reset-password.component').then((m) => m.ResetPasswordComponent) },
  { path: 'volunteer/join', loadComponent: () => import('./pages/volunteer-join/volunteer-join.component').then((m) => m.VolunteerJoinComponent) },
  { path: 'admin/volunteer-invites', loadComponent: () => import('./pages/admin-invites/admin-invites.component').then((m) => m.AdminInvitesComponent) },
  { path: 'admin/submissions', loadComponent: () => import('./pages/admin-submissions/admin-submissions.component').then((m) => m.AdminSubmissionsComponent) },
  { path: 'admin/claims', loadComponent: () => import('./pages/admin-claims/admin-claims.component').then((m) => m.AdminClaimsComponent) },
  { path: 'admin/scraper-runs', loadComponent: () => import('./pages/admin-scraper-runs/admin-scraper-runs.component').then((m) => m.AdminScraperRunsComponent) },
  { path: 'admin/reports', loadComponent: () => import('./pages/admin-reports/admin-reports.component').then((m) => m.AdminReportsComponent) },
  { path: 'go', loadComponent: () => import('./pages/nearest-redirect/nearest-redirect.component').then((m) => m.NearestRedirectComponent) },
  { path: 'volunteer/field-kit', loadComponent: () => import('./pages/volunteer-field-kit/volunteer-field-kit.component').then((m) => m.VolunteerFieldKitComponent) },
  { path: 'events', loadComponent: () => import('./pages/event-list/event-list.component').then((m) => m.EventListComponent) },
  { path: 'events/new', loadComponent: () => import('./pages/event-form/event-form.component').then((m) => m.EventFormComponent) },
  { path: 'events/:id/edit', loadComponent: () => import('./pages/event-form/event-form.component').then((m) => m.EventFormComponent) },
  { path: 'events/:id', loadComponent: () => import('./pages/event-detail/event-detail.component').then((m) => m.EventDetailComponent) },
  { path: 'churches/new', loadComponent: () => import('./pages/church-form/church-form.component').then((m) => m.ChurchFormComponent) },
  { path: 'churches/:id/edit', loadComponent: () => import('./pages/church-form/church-form.component').then((m) => m.ChurchFormComponent) },
  { path: 'churches/:id/images', loadComponent: () => import('./pages/church-images/church-images.component').then((m) => m.ChurchImagesComponent) },
  { path: 'churches/:id/sermons', loadComponent: () => import('./pages/church-sermons/church-sermons.component').then((m) => m.ChurchSermonsComponent) },
  { path: 'churches/:id/staff', loadComponent: () => import('./pages/church-staff/church-staff.component').then((m) => m.ChurchStaffComponent) },
  { path: 'churches/:id', loadComponent: () => import('./pages/church-profile/church-profile.component').then((m) => m.ChurchProfileComponent) },
];
