import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-auth',
  standalone: true,
  imports: [RouterModule, ReactiveFormsModule],
  templateUrl: './auth.component.html',
})
export class AuthComponent {
  private fb = inject(FormBuilder);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private authService = inject(AuthService);

  mode: 'login' | 'register' = this.route.snapshot.data['mode'] === 'register' ? 'register' : 'login';

  form = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  submitting = false;
  error = '';

  submit() {
    if (this.form.invalid) return;
    this.submitting = true;
    this.error = '';

    const { email, password } = this.form.getRawValue();
    const obs =
      this.mode === 'register'
        ? this.authService.register(email!, password!)
        : this.authService.login(email!, password!);

    obs.subscribe({
      next: () => this.router.navigate(['/churches']),
      error: (err) => {
        this.submitting = false;
        this.error = err.error?.error ?? 'Something went wrong. Please try again.';
      },
    });
  }
}
