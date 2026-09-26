package com.everychurch.server.service;

import com.everychurch.server.dto.BlockStatusDto;
import com.everychurch.server.model.Block;
import com.everychurch.server.model.User;
import com.everychurch.server.repository.BlockRepository;
import com.everychurch.server.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/** DM blocking — see Block's class comment for exactly what this does and doesn't
 *  gate (direct messages only, in this pass). */
@Service
@Transactional
public class BlockService {

    private final BlockRepository blockRepository;
    private final UserRepository userRepository;

    public BlockService(BlockRepository blockRepository, UserRepository userRepository) {
        this.blockRepository = blockRepository;
        this.userRepository = userRepository;
    }

    public void block(User blocker, Long blockedId) {
        if (blockedId.equals(blocker.getId())) {
            throw new IllegalArgumentException("You can't block yourself");
        }
        if (blockRepository.existsByBlockerIdAndBlockedId(blocker.getId(), blockedId)) {
            throw new IllegalStateException("Already blocked");
        }
        User blocked = userRepository.findById(blockedId)
            .orElseThrow(() -> new NoSuchElementException("No such user"));

        Block block = new Block();
        block.setBlocker(blocker);
        block.setBlocked(blocked);
        blockRepository.save(block);
    }

    public void unblock(User blocker, Long blockedId) {
        Block block = blockRepository.findByBlockerIdAndBlockedId(blocker.getId(), blockedId)
            .orElseThrow(() -> new NoSuchElementException("Not blocked"));
        blockRepository.delete(block);
    }

    @Transactional(readOnly = true)
    public BlockStatusDto status(User blocker, Long blockedId) {
        return new BlockStatusDto(blockRepository.existsByBlockerIdAndBlockedId(blocker.getId(), blockedId));
    }

    /** Used by DirectMessageService before every send — true if EITHER user has
     *  blocked the other, not just the sender blocking the recipient. */
    @Transactional(readOnly = true)
    public boolean isBlockedEitherDirection(Long userA, Long userB) {
        return blockRepository.existsEitherDirection(userA, userB);
    }
}
