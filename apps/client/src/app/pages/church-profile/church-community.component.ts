import { Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterModule } from '@angular/router';
import { Observable } from 'rxjs';
import { Member, MembershipStatus } from '../../models/member.model';
import { AuthService } from '../../services/auth.service';
import { MemberService } from '../../services/member.service';
import { ChurchChatComponent } from './church-chat.component';

/**
 * The congregation-private half of a church profile: join/leave, the member list, and
 * (Prompt 15) the group chat. The server enforces who may see any of this — the checks
 * here only decide what to show, so a member never sees a button that would be refused.
 */
@Component({
  selector: 'app-church-community',
  standalone: true,
  imports: [RouterModule, ChurchChatComponent],
  host: { class: 'block' },
  templateUrl: './church-community.component.html',
})
export class ChurchCommunityComponent implements OnInit {
  churchId = input.required<number>();

  private memberService = inject(MemberService);
  authService = inject(AuthService);

  status = signal<MembershipStatus | null>(null);
  members = signal<Member[]>([]);
  loading = signal(true);
  busy = signal(false);
  error = signal('');

  ngOnInit() {
    if (!this.authService.isAuthenticated()) {
      this.loading.set(false);
      return;
    }
    this.refresh();
  }

  private refresh() {
    this.memberService.status(this.churchId()).subscribe({
      next: (status) => {
        this.status.set(status);
        this.loading.set(false);
        if (status.canChat) this.loadMembers();
        else this.members.set([]);
      },
      error: () => {
        this.error.set('Could not load your membership status.');
        this.loading.set(false);
      },
    });
  }

  private loadMembers() {
    this.memberService.list(this.churchId()).subscribe({
      next: (members) => this.members.set(members),
      error: () => this.members.set([]),
    });
  }

  join() {
    this.act(this.memberService.join(this.churchId()), 'Could not join this church.');
  }

  leave() {
    this.act(this.memberService.leave(this.churchId()), 'Could not leave this church.');
  }

  private act(request: Observable<unknown>, failure: string) {
    this.busy.set(true);
    this.error.set('');
    request.subscribe({
      next: () => {
        this.busy.set(false);
        this.refresh();
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(err.error?.error ?? failure);
      },
    });
  }
}
