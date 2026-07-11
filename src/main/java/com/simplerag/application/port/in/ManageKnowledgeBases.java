package com.simplerag.application.port.in;

import com.simplerag.model.KnowledgeBase;

import java.io.IOException;
import java.util.List;

public interface ManageKnowledgeBases {
    List<KnowledgeBase> knowledgeBases();
    KnowledgeBase currentKnowledgeBase();
    KnowledgeBase createKnowledgeBase(String name, String description);
    KnowledgeBase updateCurrentKnowledgeBase(String name, String description);
    void deleteKnowledgeBase(String id) throws IOException;
    boolean selectKnowledgeBase(String id);
}
