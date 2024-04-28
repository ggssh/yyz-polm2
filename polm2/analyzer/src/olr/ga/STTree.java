/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package olr.ga;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class STTree {
    
    /**
     * Maps ste ids to sttree nodes.
     */
    private final Map<Integer, STTree> children = new HashMap<>();
    
    /**
     * Parent sttree node.
     */
    private final STTree parent;
    
    /** 
     * Set of stack trace IDs that use this sttree node.
     */
    private final Set<Integer> stIDs;
    
    /** 
     * The stack trace element that corresponds to this sttree node.
     */
    private final StackTraceElement ste;
    
    public STTree() {
        this(null, null);
    }

    public STTree(STTree parent, StackTraceElement ste) {
        this.ste = ste;
        this.parent = parent;
        stIDs = new HashSet<>();
    }    
    
    public boolean isRoot() {
        return parent == null;
    }
    
    public boolean isLeaf() {
        return children.isEmpty();
    }
    
    public Map<Integer, STTree> children() {
        return children;
    }
    
    public void addChild(Integer key, STTree child) {
        children.put(key, child);
    }
   
    public StackTraceElement getStrackTraceElement() {
        return ste;
    }
    
    public void addSTID(Integer stID) {
        stIDs.add(stID);
    }

    public Set<Integer> getSTIDs() {
        return stIDs;
    }

    public String toOutput() {
        String output = "";

        if (isLeaf()) {
            for (Integer id : stIDs) {
                int gen = ObjectGraphAnalyzer.targetGeneration(ObjectGraphAnalyzer.lifetimes.get(id));
                if (gen > 0) {
                    // Note: the parent.parent thing is because the allocation recorder introduces two
                    // stes at the end of the allocation stack trace.
                    output += String.format("gen=%d ste=%s\n", gen, parent.parent.ste.toString());
                }
            }
        }

        for (STTree sttree : children.values()) {
            output += sttree.toOutput();
        }
        return output;
    }

    public String toXML() {
        String xml = "";
        if (isRoot()) {
            xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
        }

        xml += String.format("<STTree ste=\"%s\">\n", ste == null ? "null" : ste.toString().replace("<", "&lt;"));

        if (isLeaf()) {
            for (Integer id : stIDs) {
                int gen = ObjectGraphAnalyzer.targetGeneration(ObjectGraphAnalyzer.lifetimes.get(id));
                xml += String.format("\t<lifetimes stid=\"%s\" gen=\"%d\">",id, gen);
                for (Set<Integer> lifetimes : ObjectGraphAnalyzer.lifetimes.get(id)) {
                    xml += " " + lifetimes.size() + " ";
                }
                xml += String.format("</lifetimes>\n",id);
            }
        }

        for (STTree sttree : children.values()) {
            xml += "\t" + sttree.toXML();
        }
        xml += "</STTree>\n";
        return xml;
    }
}
