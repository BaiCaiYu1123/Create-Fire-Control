// SPDX-License-Identifier: MIT
// Copyright (c) 2026 caca
// CopyPoly 1.9.1; see third_party/copypoly/LICENSE.

package com.caca.copypoly.client;

import com.caca.copypoly.api.PolyShape;
import java.util.*;

/** Immutable document snapshots: no camera movement or merely changing selection in undo. */
final class ShapeHistory {
    record State(PolyShape shape,List<Integer> selection,List<Integer> faces,boolean axes,boolean faceMode,PolyShape.Connection connection,int mode,List<PolyShape.Connection> connections){State{selection=List.copyOf(selection);faces=List.copyOf(faces);connections=List.copyOf(connections);}}
    private final ArrayDeque<State> past=new ArrayDeque<>();
    void remember(PolyShape shape,List<Integer> selection) {
        remember(shape,selection,List.of(),true,false);
    }
    void remember(PolyShape shape,List<Integer> selection,Collection<Integer> faces,boolean axes,boolean faceMode) {
        remember(shape,selection,faces,axes,faceMode,null);
    }
    void remember(PolyShape shape,List<Integer> selection,Collection<Integer> faces,boolean axes,boolean faceMode,PolyShape.Connection connection) {
        remember(shape,selection,faces,axes,faceMode,connection,faceMode?2:connection==null?0:1,connection==null?List.of():List.of(connection));
    }
    void remember(PolyShape shape,List<Integer> selection,Collection<Integer> faces,boolean axes,boolean faceMode,PolyShape.Connection connection,int mode,Collection<PolyShape.Connection> connections) {
        past.addLast(new State(shape,selection,List.copyOf(faces),axes,faceMode,connection,mode,List.copyOf(connections)));if(past.size()>100)past.removeFirst();
    }
    State undo(){return past.pollLast();}
}
