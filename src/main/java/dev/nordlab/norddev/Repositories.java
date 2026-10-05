package dev.nordlab.norddev;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

interface AccountRepository extends JpaRepository<Account,Long> { Optional<Account> findByEmailIgnoreCase(String email); long countByRole(String role); }
interface LicenseRepository extends JpaRepository<License,Long> { List<License> findAllByOrderByCreatedAtDesc(); Optional<License> findByKeyHash(String keyHash); }
interface ServiceProjectRepository extends JpaRepository<ServiceProject,Long> { List<ServiceProject> findByOwnerEmailOrderByCreatedAtDesc(String email); }
interface ReleaseRepository extends JpaRepository<Release,Long> { List<Release> findAllByOrderByCreatedAtDesc(); Optional<Release> findFirstByChannelAndStatusOrderByCreatedAtDesc(String channel,String status); }
