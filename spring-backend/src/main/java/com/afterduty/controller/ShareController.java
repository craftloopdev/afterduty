package com.afterduty.controller;

import com.afterduty.dto.CreateShareRequest;
import com.afterduty.dto.PatchShareRequest;
import com.afterduty.dto.ProfileSummaryDto;
import com.afterduty.dto.ShareDto;
import com.afterduty.dto.SharePreviewDto;
import com.afterduty.model.User;
import com.afterduty.service.ShareService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Phase B: share lifecycle endpoints.
 *
 * <p>All authenticated endpoints call {@link AuthController#getCurrentUser} —
 * package-private static method accessible from this same package.
 * GET /api/shares/accept/{token} is public (SecurityConfig skips auth for it).
 */
@RestController
@RequestMapping("/api/shares")
public class ShareController {

    private final ShareService shareService;

    public ShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    // POST /api/shares — create a share invitation
    @PostMapping
    public ResponseEntity<ShareDto> createShare(
            @Valid @RequestBody CreateShareRequest req,
            HttpServletRequest request) {
        User owner = AuthController.getCurrentUser(request);
        ShareDto dto = shareService.createShare(owner, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    // GET /api/shares — list all non-revoked shares owned by current user
    @GetMapping
    public List<ShareDto> listShares(HttpServletRequest request) {
        User owner = AuthController.getCurrentUser(request);
        return shareService.listShares(owner);
    }

    // PATCH /api/shares/{id} — update permissions on a share
    @PatchMapping("/{id}")
    public ShareDto patchShare(
            @PathVariable Long id,
            @RequestBody PatchShareRequest req,
            HttpServletRequest request) {
        User owner = AuthController.getCurrentUser(request);
        return shareService.patchShare(owner, id, req);
    }

    // DELETE /api/shares/{id} — revoke a share (sets revokedAt, does not delete row)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeShare(
            @PathVariable Long id,
            HttpServletRequest request) {
        User owner = AuthController.getCurrentUser(request);
        shareService.revokeShare(owner, id);
    }

    // GET /api/shares/accept/{token} — public preview (no auth required)
    @GetMapping("/accept/{token}")
    public SharePreviewDto previewShare(@PathVariable String token) {
        return shareService.previewShare(token);
    }

    // POST /api/shares/accept/{token} — accept a share (viewer must be authenticated)
    @PostMapping("/accept/{token}")
    public ShareDto acceptShare(
            @PathVariable String token,
            HttpServletRequest request) {
        User viewer = AuthController.getCurrentUser(request);
        return shareService.acceptShare(viewer, token);
    }

    // GET /api/shares/profiles — list all claims current user can access
    @GetMapping("/profiles")
    public List<ProfileSummaryDto> listProfiles(HttpServletRequest request) {
        User currentUser = AuthController.getCurrentUser(request);
        return shareService.listProfiles(currentUser);
    }
}
