package dev.nordlab.norddev;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;

interface AccountRepository extends JpaRepository<Account,Long> { Optional<Account> findByEmailIgnoreCase(String email); long countByRole(String role); List<Account> findAllByOrderByCreatedAtAsc(); }
interface PlatformSettingsRepository extends JpaRepository<PlatformSettings,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from PlatformSettings p where p.id = :id") Optional<PlatformSettings> lockGlobal(@Param("id") String id);
}
interface LicenseRepository extends JpaRepository<License,Long> { List<License> findAllByOrderByCreatedAtDesc(); Optional<License> findByKeyHash(String keyHash); }
interface ServiceProjectRepository extends JpaRepository<ServiceProject,Long> { List<ServiceProject> findByOwnerEmailOrderByCreatedAtDesc(String email); }
interface ReleaseRepository extends JpaRepository<Release,Long> { List<Release> findAllByOrderByCreatedAtDesc(); Optional<Release> findFirstByChannelAndStatusOrderByCreatedAtDesc(String channel,String status); }
interface WorkspaceRepository extends JpaRepository<Workspace,Long> {
    List<Workspace> findAllByAreaOrderByCreatedAtDesc(String area);
    List<Workspace> findAllByOrderByCreatedAtDesc();
    List<Workspace> findAllByOwnerEmailIgnoreCaseAndAreaOrderByCreatedAtDesc(String ownerEmail,String area);
}
interface WorkspaceMessageRepository extends JpaRepository<WorkspaceMessage,Long> {
    List<WorkspaceMessage> findTop50ByWorkspaceIdAndChannelOrderByCreatedAtAsc(Long workspaceId,String channel);
}
