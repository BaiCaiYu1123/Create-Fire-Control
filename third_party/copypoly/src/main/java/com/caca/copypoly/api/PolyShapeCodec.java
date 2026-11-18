// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.api;

import com.caca.copypoly.geometry.PolyShapeText;
import com.caca.copypoly.geometry.ShapeBounds;
import com.caca.copypoly.geometry.Polyhedron;
import com.caca.copypoly.geometry.VertexEditing;
import java.util.*;

/** Versioned document/selection clipboard interchange, plus legacy coordinate imports. */
public final class PolyShapeCodec {
    private static final String DOCUMENT="CopyPolyGraph 1\n",SELECTION="CopyPolyVertices 1\n";
    public static final int MAX_TEXT=128*1024;
    private static String data(PolyShape shape){return String.join(",",Arrays.stream(shape.packed()).mapToObj(Integer::toString).toList());}
    public static String toText(PolyShape shape){if(shape.manualMesh() || shape.lineMesh())shape.draftSurface();else shape.compile();return DOCUMENT+data(shape);}
    private static PolyShape packed(String text,String header,ShapeBounds bounds) {
        if(text.length()>MAX_TEXT)throw new IllegalArgumentException("count");
        String[] parts=text.substring(header.length()).strip().split(",",-1);
        if(parts.length>PolyShape.maxPacked(bounds))throw new IllegalArgumentException("graph");
        int[] data=new int[parts.length];
        try{for(int i=0;i<parts.length;i++)data[i]=Integer.parseInt(parts[i].strip());}
        catch(NumberFormatException bad){throw new IllegalArgumentException("format");}
        return PolyShape.fromPacked(data,bounds);
    }
    public static PolyShape fromText(String text) {
        return fromText(text, ShapeBounds.BLOCK);
    }
    public static PolyShape fromText(String text,ShapeBounds bounds) {
        text=text.replace("\r\n","\n").strip();
        var shape=text.startsWith(DOCUMENT)?packed(text,DOCUMENT,bounds):PolyShape.fromGeometry(PolyShapeText.read(text,bounds));if(shape.manualMesh() || shape.lineMesh())shape.draftSurface();else shape.compile();return shape;
    }
    public static String copyVertices(PolyShape shape,Collection<Integer> selected) {
        Set<Integer> wanted=new HashSet<>(selected);var vertices=shape.vertices().stream().filter(v->wanted.contains(v.id())).toList();
        if(vertices.isEmpty())throw new IllegalArgumentException("no_selection");
        boolean internalLinks=shape.connections().stream().anyMatch(c->wanted.contains(c.source()) && wanted.contains(c.target()));
        if(vertices.stream().allMatch(v->v.type()==VertexType.NORMAL) && !internalLinks)return String.join("\n",vertices.stream().map(v->VertexEditing.format(v.position().x())+", "+VertexEditing.format(v.position().y())+", "+VertexEditing.format(v.position().z())).toList());
        var builder=PolyShape.builder(shape.bounds());
        if(shape.bounds().multiBlock())builder.meshFaces(List.of());
        Map<Integer,Integer> ids=new HashMap<>();
        for(var v:vertices)ids.put(v.id(),builder.addVertex(v.type(),v.position()));
        for(var c:shape.connections())if(ids.containsKey(c.source()) && ids.containsKey(c.target())){
            if(shape.bounds().multiBlock())builder.guide(ids.get(c.source()),ids.get(c.target()));
            else builder.connect(ids.get(c.source()),ids.get(c.target()));
        }
        return SELECTION+data(builder.build());
    }
    public static PolyShape readVertices(String text) {
        return readVertices(text, ShapeBounds.BLOCK);
    }
    public static PolyShape readVertices(String text,ShapeBounds bounds) {
        text=text.replace("\r\n","\n").strip();if(text.length()>MAX_TEXT)throw new IllegalArgumentException("paste_limit");
        if(text.startsWith(SELECTION))return packed(text,SELECTION,bounds);
        var points=Polyhedron.parse(text,bounds);if(points.isEmpty())throw new IllegalArgumentException("format");
        var builder=PolyShape.builder(bounds);for(var p:points)builder.addVertex(VertexType.NORMAL,p);return builder.build();
    }
    private PolyShapeCodec(){}
}
