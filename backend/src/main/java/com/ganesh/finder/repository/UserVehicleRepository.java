package com.ganesh.finder.repository;

import com.ganesh.finder.model.UserVehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface UserVehicleRepository extends JpaRepository<UserVehicle, Long> {
    List<UserVehicle> findByUserId(String userId);
    void deleteByUserId(String userId);
}
