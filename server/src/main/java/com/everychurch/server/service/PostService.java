package com.everychurch.server.service;

import com.everychurch.server.dto.PostDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.ChurchPermission;
import com.everychurch.server.model.Post;
import com.everychurch.server.model.Role;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.ChurchRepository;
import com.everychurch.server.repository.ChurchStaffRepository;
import com.everychurch.server.repository.PostRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/** Church-authored posts — the content half of the "church social media" follow
 *  feature (see Follow's class comment). Who may post is decided the same way as
 *  events/sermons: MANAGE_POSTS on that church, or PLATFORM_ADMIN. */
@Service
@Transactional
public class PostService {

    public static final int MAX_BODY_LENGTH = 5000;
    public static final int DEFAULT_PAGE_LIMIT = 20;
    public static final int MAX_PAGE_LIMIT = 50;

    private final PostRepository postRepository;
    private final ChurchRepository churchRepository;
    private final ChurchStaffRepository churchStaffRepository;

    public PostService(PostRepository postRepository, ChurchRepository churchRepository, ChurchStaffRepository churchStaffRepository) {
        this.postRepository = postRepository;
        this.churchRepository = churchRepository;
        this.churchStaffRepository = churchStaffRepository;
    }

    public PostDto create(Long churchId, User author, String body) {
        requireCanManage(churchId, author);
        Church church = churchRepository.findById(churchId)
            .orElseThrow(() -> new NoSuchElementException("No such church"));

        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A post can't be empty");
        }
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("A post can be at most " + MAX_BODY_LENGTH + " characters");
        }

        Post post = new Post();
        post.setChurch(church);
        post.setAuthor(author);
        post.setBody(trimmed);
        return toDto(postRepository.save(post));
    }

    public void delete(Long churchId, Long postId, User requester) {
        requireCanManage(churchId, requester);
        Post post = postRepository.findById(postId)
            .orElseThrow(() -> new NoSuchElementException("No such post"));
        if (!post.getChurch().getId().equals(churchId)) {
            throw new NoSuchElementException("No such post for this church");
        }
        postRepository.delete(post);
    }

    @Transactional(readOnly = true)
    public List<PostDto> list(Long churchId, Long beforeId, Integer limit) {
        int pageSize = pageSize(limit);
        var page = PageRequest.of(0, pageSize);
        List<Post> posts = beforeId == null
            ? postRepository.findByChurchIdOrderByIdDesc(churchId, page)
            : postRepository.findByChurchIdAndIdLessThanOrderByIdDesc(churchId, beforeId, page);
        return posts.stream().map(this::toDto).toList();
    }

    /** Newest first across every church the user follows — not paged per church, one
     *  merged timeline, same cursor convention as chat/DM history (pass the id of the
     *  oldest post already loaded to get the page before it). */
    @Transactional(readOnly = true)
    public List<PostDto> feed(User user, Long beforeId, Integer limit) {
        int pageSize = pageSize(limit);
        var page = PageRequest.of(0, pageSize);
        List<Post> posts = beforeId == null
            ? postRepository.findFeedForUser(user.getId(), page)
            : postRepository.findFeedForUserBefore(user.getId(), beforeId, page);
        return posts.stream().map(this::toDto).toList();
    }

    private int pageSize(Integer limit) {
        return limit == null ? DEFAULT_PAGE_LIMIT : Math.max(1, Math.min(limit, MAX_PAGE_LIMIT));
    }

    /** Same shape as EventService.requireCanManage: PLATFORM_ADMIN bypasses, everyone
     *  else needs MANAGE_POSTS staff on this specific church. */
    private void requireCanManage(Long churchId, User requester) {
        if (requester.getRole() == Role.PLATFORM_ADMIN) return;

        boolean canManage = churchStaffRepository.findByChurchIdAndUserId(churchId, requester.getId())
            .map(staff -> staff.hasPermission(ChurchPermission.MANAGE_POSTS))
            .orElse(false);

        if (!canManage) {
            throw new AccessDeniedException("You don't have permission to post for this church");
        }
    }

    private PostDto toDto(Post post) {
        return new PostDto(
            post.getId(), post.getChurch().getId(), post.getChurch().getName(),
            post.getAuthor().getId(), post.getAuthor().getDisplayName(),
            post.getBody(), post.getImageUrl(), post.getCreatedAt());
    }
}
