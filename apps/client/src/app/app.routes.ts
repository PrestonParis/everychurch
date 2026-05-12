import { Route } from '@angular/router';
import { ChurchListComponent } from './pages/church-list/church-list.component';
import { ChurchProfileComponent } from './pages/church-profile/church-profile.component';
import { ChurchFormComponent } from './pages/church-form/church-form.component';

export const appRoutes: Route[] = [
  { path: '', redirectTo: '/churches', pathMatch: 'full' },
  { path: 'churches', component: ChurchListComponent },
  { path: 'churches/new', component: ChurchFormComponent },
  { path: 'churches/:id/edit', component: ChurchFormComponent },
  { path: 'churches/:id', component: ChurchProfileComponent },
];
