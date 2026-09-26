import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [RouterModule, ReactiveFormsModule],
  templateUrl: './reset-password.component.html',
})
export class ResetPasswordComponent {
  private fb = inject(FormBuilder);
  private router = inject(Router);
  private authService = inject(AuthService);

  // Read once at construction (a query param that changes without a full navigation
  // isn't a case this page needs to handle) rather than via an async pipe/subscribe —
  // simpler, and matches how AuthComponent reads its route data.
  private readonly token = inject(ActivatedRoute).snapshot.queryParamMap.get('token') ?? '';
  readonly hasToken = this.token.length > 0;

  form = this.fb.group({
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  private readonly _submitting = signal(false);
  private readonly _done = signal(false);
  private readonly _error = signal('');
  private readonly _showPassword = signal(false);
  get submitting(): boolean { return this._submitting(); }
  get done(): boolean { return this._done(); }
  get error(): string { return this._error(); }
  get showPassword(): boolean { return this._showPassword(); }

  toggleShowPassword() {
    this._showPassword.update((v) => !v);
  }

  submit() {
    if (this.form.invalid || this.submitting || !this.hasToken) return;
    this._submitting.set(true);
    this._error.set('');

    this.authService.resetPassword(this.token, this.form.getRawValue().password!).subscribe({
      next: () => this._done.set(true),
      error: (err) => {
        this._submitting.set(false);
        this._error.set(err.error?.error ?? 'This reset link is invalid or has expired.');
      },
    });
  }

  goToLogin() {
    this.router.navigate(['/login']);
  }
}
