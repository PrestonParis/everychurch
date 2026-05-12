package com.everychurch.server.service;

import com.everychurch.server.dto.ChurchDto;
import com.everychurch.server.dto.ServiceTimeDto;
import com.everychurch.server.model.Church;
import com.everychurch.server.model.ServiceTime;
import com.everychurch.server.repository.ChurchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
@Transactional
public class ChurchService {

    private final ChurchRepository repo;

    public ChurchService(ChurchRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<ChurchDto> findAll() {
        return repo.findAll().stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ChurchDto findById(Long id) {
        return repo.findById(id)
            .map(this::toDto)
            .orElseThrow(() -> new NoSuchElementException("Church not found: " + id));
    }

    public ChurchDto create(ChurchDto dto) {
        Church church = new Church();
        applyDto(church, dto);
        return toDto(repo.save(church));
    }

    public ChurchDto update(Long id, ChurchDto dto) {
        Church church = repo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Church not found: " + id));
        applyDto(church, dto);
        return toDto(repo.save(church));
    }

    public void delete(Long id) {
        repo.deleteById(id);
    }

    private void applyDto(Church c, ChurchDto dto) {
        c.setName(dto.name());
        c.setDenomination(dto.denomination());
        c.setAddress(dto.address());
        c.setCity(dto.city());
        c.setState(dto.state());
        c.setZipCode(dto.zipCode());
        c.setPhone(dto.phone());
        c.setEmail(dto.email());
        c.setWebsite(dto.website());
        c.setDescription(dto.description());
        c.setImageUrl(dto.imageUrl());
        c.setPastor(dto.pastor());
        c.setFacebook(dto.facebook());
        c.setInstagram(dto.instagram());
        c.setYoutube(dto.youtube());
        c.setClaimed(dto.claimed() != null && dto.claimed());

        c.getServiceTimes().clear();
        if (dto.serviceTimes() != null) {
            for (ServiceTimeDto st : dto.serviceTimes()) {
                ServiceTime s = new ServiceTime();
                s.setDayOfWeek(st.dayOfWeek());
                s.setTime(st.time());
                s.setType(st.type());
                s.setLanguage(st.language());
                s.setChurch(c);
                c.getServiceTimes().add(s);
            }
        }
    }

    private ChurchDto toDto(Church c) {
        List<ServiceTimeDto> times = c.getServiceTimes().stream()
            .map(s -> new ServiceTimeDto(s.getId(), s.getDayOfWeek(), s.getTime(), s.getType(), s.getLanguage()))
            .toList();
        return new ChurchDto(
            c.getId(), c.getName(), c.getDenomination(),
            c.getAddress(), c.getCity(), c.getState(), c.getZipCode(),
            c.getPhone(), c.getEmail(), c.getWebsite(),
            c.getDescription(), c.getImageUrl(), c.getPastor(),
            c.getFacebook(), c.getInstagram(), c.getYoutube(),
            c.isClaimed(), times
        );
    }
}
