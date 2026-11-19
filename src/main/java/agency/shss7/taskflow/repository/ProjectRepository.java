package agency.shss7.taskflow.repository;

import agency.shss7.taskflow.domain.Project;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    @Override
    @EntityGraph(attributePaths = {"owner"})
    List<Project> findAll();

    @EntityGraph(attributePaths = {"owner"})
    Optional<Project> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);
}
