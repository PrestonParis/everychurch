import { Component, inject, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-forgot-password',
  standalone: true,
  imports: [RouterModule, ReactiveFormsModule],
  templateUrl: './forgot-password.component.html',
})
export class ForgotPasswordComponent {
  private fb = inject(FormBuilder);
  private authService = inject(AuthService);

  form = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
  });

  // Signal-backed so an HTTP callback re-renders the page (the app is zoneless — see
  // AuthComponent's identical comment on why a plain field wouldn't repaint).
  private readonly _submitting = signal(false);
  private readonly _submitted = signal(false);
  private readonly _error = signal('');
  get submitting(): boolean { return this._submitting(); }
  get submitted(): boolean { return this._submitted(); }
  get error(): string { return this._error(); }

  submit() {
    if (this.form.invalid || this.submitting) return;
    this._submitting.set(true);
    this._error.set('');

    this.authService.forgotPassword(this.form.getRawValue().email!).subscribe({
      // The server always answers 200 whether or not the email has an account — that's
      // the enumeration protection, not something to also fake client-side. A real
      // network/server failure is a different thing and should say so, not silently
      // masquerade as "check your email".
      next: () => this._submitted.set(true),
      error: () => {
        this._submitting.set(false);
        this._error.set('Something went wrong. Please try again.');
      },
    });
  }
}
