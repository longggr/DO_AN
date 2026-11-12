package org.example.smashhub.location.repository;

import org.example.smashhub.location.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WardRepository extends JpaRepository<Ward, String> {
    List<Ward> findByDistrictIdOrderByNameAsc(Integer districtId);
}

