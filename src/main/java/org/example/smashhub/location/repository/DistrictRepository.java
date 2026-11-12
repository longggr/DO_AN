package org.example.smashhub.location.repository;

import org.example.smashhub.location.entity.District;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DistrictRepository extends JpaRepository<District, Integer> {
    List<District> findByProvinceIdOrderByNameAsc(Integer provinceId);
}

