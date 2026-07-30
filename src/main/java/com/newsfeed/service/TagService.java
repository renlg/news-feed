package com.newsfeed.service;

import com.newsfeed.model.Tag;
import com.newsfeed.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;

    public List<Tag> findAll() {
        return tagRepository.findAllByOrderByName();
    }

    public Optional<Tag> findById(Long id) {
        return tagRepository.findById(id);
    }

    @Transactional
    public Tag save(Tag tag) {
        return tagRepository.save(tag);
    }

    @Transactional
    public void delete(Long id) {
        tagRepository.deleteById(id);
    }
}
