import { Route } from '@angular/router';
import { ChurchListComponent } from './pages/church-list/church-list.component';
import { ChurchProfileComponent } from './pages/church-profile/church-profile.component';
import { ChurchFormComponent } from './pages/church-form/church-form.component';
import { AuthComponent } from './pages/auth/auth.component';
import { VolunteerJoinComponent } from './pages/volunteer-join/volunteer-join.component';
import { AdminInvitesComponent } from './pages/admin-invites/admin-invites.component';
import { AdminSubmissionsComponent } from './pages/admin-submissions/admin-submissions.component';
import { AdminScraperRunsComponent } from './pages/admin-scraper-runs/admin-scraper-runs.component';
import { NearestRedirectComponent } from './pages/nearest-redirect/nearest-redirect.component';
import { VolunteerFieldKitComponent } from './pages/volunteer-field-kit/volunteer-field-kit.component';
import { ChurchImagesComponent } from './pages/church-images/church-images.component';
import { ChurchSermonsComponent } from './pages/church-sermons/church-sermons.component';
import { EventListComponent } from './pages/event-list/event-list.component';
import { EventDetailComponent } from './pages/event-detail/event-detail.component';
import { EventFormComponent } from './pages/event-form/event-form.component';

export const appRoutes: Route[] = [
  { path: '', redirectTo: '/churches', pathMatch: 'full' },
  { path: 'login', component: AuthComponent, data: { mode: 'login' } },
  { path: 'register', component: AuthComponent, data: { mode: 'register' } },
  { path: 'volunteer/join', component: VolunteerJoinComponent },
  { path: 'admin/volunteer-invites', component: AdminInvitesComponent },
  { path: 'admin/submissions', component: AdminSubmissionsComponent },
  { path: 'admin/scraper-runs', component: AdminScraperRunsComponent },
  { path: 'go', component: NearestRedirectComponent },
  { path: 'volunteer/field-kit', component: VolunteerFieldKitComponent },
  { path: 'events', component: EventListComponent },
  { path: 'events/new', component: EventFormComponent },
  { path: 'events/:id/edit', component: EventFormComponent },
  { path: 'events/:id', component: EventDetailComponent },
  { path: 'churches', component: ChurchListComponent },
  { path: 'churches/new', component: ChurchFormComponent },
  { path: 'churches/:id/edit', component: ChurchFormComponent },
  { path: 'churches/:id/images', component: ChurchImagesComponent },
  { path: 'churches/:id/sermons', component: ChurchSermonsComponent },
  { path: 'churches/:id', component: ChurchProfileComponent },
];
