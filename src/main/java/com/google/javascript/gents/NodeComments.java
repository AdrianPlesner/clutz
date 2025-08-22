package com.google.javascript.gents;

import java.util.HashMap;
import java.util.Map;

import com.google.javascript.rhino.Node;

/** Represents the mapping from an AST Node to its corresponding comment. */
class NodeComments {
    private final Map<Node, String> nodeToComment = new HashMap<>();

    void addComment(Node n, String comment) {
        if (hasComment(n)) {
            comment = getComment(n) + comment;
        }
        setComment(n, comment);
    }

    void setComment(Node n, String comment) {
        this.nodeToComment.put(n, comment);
    }

    boolean hasComment(Node n) {
        return this.nodeToComment.containsKey(n);
    }

    String getComment(Node n) {
        return this.nodeToComment.get(n);
    }

    void clearComment(Node n) {
        this.nodeToComment.remove(n);
    }

    void moveComment(Node from, Node to) {
        if (getComment(from) != null) {
            addComment(to, getComment(from));
            clearComment(from);
        }
    }

    void replaceWithComment(Node oldNode, Node newNode) {
        newNode.useSourceInfoFrom(newNode);
        oldNode.getParent().replaceChild(oldNode, newNode);
        moveComment(oldNode, newNode);
    }
}
