package com.google.javascript.gents;

import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.rhino.Node;

/** Abstract callback to visit all top level statement nodes but doesn't traverse any deeper. */
public abstract class AbstractTopLevelCallback implements NodeTraversal.Callback {
    @Override
    public final boolean shouldTraverse(NodeTraversal nodeTraversal, Node n, Node parent) {
        return parent == null || n.isScript() || parent.isScript() || parent.isModuleBody();
    }
}
