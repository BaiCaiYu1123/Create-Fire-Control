// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.client;

import com.caca.copypoly.geometry.Polyhedron.Point;
import com.caca.copypoly.geometry.VertexEditing;
import com.caca.copypoly.geometry.ShapeBounds;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.*;
import java.util.function.Function;

/** Shared picking and axis handles for the preview and world editors. */
final class PolyGizmo {
    @FunctionalInterface interface AxisCoordinate { double at(double x, double y, Vec3 origin, int axis); }
    int selected = -1;
    private final Set<Integer> selection=new LinkedHashSet<>();
    private int dragAxis = -1;
    private Map<Integer,Point> startPoints=Map.of();
    private Vec3 startOrigin;
    private double startParameter;
    private AxisCoordinate coordinate;
    private final ShapeBounds bounds;
    PolyGizmo() { this(ShapeBounds.BLOCK); }
    PolyGizmo(ShapeBounds bounds) { this.bounds = bounds; }

    static Vec3 position(Point p) { return new Vec3(p.x()/16, p.y()/16, p.z()/16); }
    static Vec3 axis(int axis) { return axis == 0 ? new Vec3(1,0,0) : axis == 1 ? new Vec3(0,1,0) : new Vec3(0,0,1); }
    boolean dragging() { return dragAxis >= 0; }
    void release() { dragAxis = -1; coordinate = null; startPoints=Map.of(); }
    void clearSelection(){selection.clear();selected=-1;release();}
    boolean isSelected(int index){return selection.contains(index);}
    int selectedCount(){return selection.size();}
    List<Integer> selectedIndices(){return selection.stream().sorted().toList();}
    List<Integer> selectionOrder(){return List.copyOf(selection);}
    static int pickVertex(double x,double y,List<Point> points,Function<Vec3,Vec3> project,int exclude) {
        return pickVertex(x,y,points,project,exclude,8);
    }
    static int pickVertex(double x,double y,List<Point> points,Function<Vec3,Vec3> project,int exclude,double radius) {
        return pickVertex(x,y,points,project,exclude,radius,p->true);
    }
    static int pickVertex(double x,double y,List<Point> points,Function<Vec3,Vec3> project,int exclude,double radius,java.util.function.Predicate<Vec3> visible) {
        int closest=-1;double best=radius*radius,depth=Double.POSITIVE_INFINITY;
        for(int i=0;i<points.size();i++) {
            if(i==exclude)continue;Vec3 p=project.apply(position(points.get(i)));if(p==null)continue;
            double d=square(p.x-x)+square(p.y-y);
            if(d<=radius*radius && (d<best-1 || d<=best+1 && p.z<depth) && visible.test(p)){closest=i;best=d;depth=p.z;}
        }
        return closest;
    }
    void replaceSelection(Collection<Integer> indices) {
        clearSelection();
        for(int index:indices){selection.add(index);selected=index;}
    }
    void select(int index,boolean multiple) {
        release();
        if(multiple && selection.contains(index)) {
            selection.remove(index);
            if(selected==index)selected=selection.isEmpty()?-1:selection.iterator().next();
        } else {
            if(!multiple)selection.clear();selection.add(index);selected=index;
        }
    }
    void removePoint(int index) {
        release();Set<Integer> shifted=new LinkedHashSet<>();
        for(int value:selection)if(value!=index)shifted.add(value>index?value-1:value);
        selection.clear();selection.addAll(shifted);
        selected=selected==index?-1:selected>index?selected-1:selected;
        if(selected<0 && !selection.isEmpty())selected=selection.iterator().next();
    }
    private Vec3 origin(List<Point> points) {
        Vec3 center=Vec3.ZERO;int count=0;
        for(int index:selection)if(index>=0 && index<points.size()){center=center.add(position(points.get(index)));count++;}
        return count==0?null:center.scale(1.0/count);
    }

    boolean clickAxes(double x, double y, List<Point> points, Function<Vec3, EditorAxisHandles> layout, AxisCoordinate mapper) {
        Vec3 origin = origin(points);
        if (origin == null) return false;
        EditorAxisHandles handles = layout.apply(origin);
        int hit = handles == null ? -1 : handles.pick(x, y);
        if (hit < 0) return false;
        double parameter = mapper.at(x,y,origin,hit);
        if (!Double.isFinite(parameter)) return false;
        dragAxis=hit;startOrigin=origin;coordinate=mapper;startParameter=parameter;
        Map<Integer,Point> snapshot=new LinkedHashMap<>();
        for(int index:selection)if(index>=0 && index<points.size())snapshot.put(index,points.get(index));
        startPoints=snapshot;
        return true;
    }

    Point drag(double x, double y, boolean fine) {
        return dragSelection(x,y,fine).get(selected);
    }
    Map<Integer,Point> dragSelection(double x,double y,boolean fine) {
        if(!dragging())return Map.of();
        double parameter=coordinate.at(x,y,startOrigin,dragAxis);
        if(!Double.isFinite(parameter))return Map.of();
        List<Point> moved=VertexEditing.translate(List.copyOf(startPoints.values()),dragAxis,parameter-startParameter,fine,bounds);
        Map<Integer,Point> result=new LinkedHashMap<>();int i=0;
        for(int index:startPoints.keySet())result.put(index,moved.get(i++));
        return result;
    }

    void drawAxes(GuiGraphics graphics, Font font, List<Point> points, Function<Vec3, EditorAxisHandles> layout) {
        Vec3 origin = origin(points);
        if (origin == null) return;
        EditorAxisHandles handles = layout.apply(origin);
        if (handles != null) handles.draw(graphics, font, dragAxis);
    }

    static void line(GuiGraphics g, Vec3 a, Vec3 b, int color, int thickness) {
        SmoothOverlay.line(g,a,b,color,3*thickness*SmoothOverlay.pixel());
    }
    private static double square(double n) { return n*n; }
}
