package org.example.smashhub.chat.ai.vectorstore;

import java.util.List;

public interface VectorStore {

    void add(List<Document> documents);

    void delete(List<String> idList);

    List<Document> similaritySearch(String query);

    List<Document> similaritySearch(SearchRequest request);
}

