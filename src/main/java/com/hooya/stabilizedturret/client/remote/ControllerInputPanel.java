package com.hooya.stabilizedturret.client.remote;

import com.hooya.stabilizedturret.content.remote.ControllerInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Function;

/** Shared keyboard diagram, mouse area and searchable complete input list. */
final class ControllerInputPanel {
    private record Key(int code, boolean mouse, float x, float y, float w, float h) {}
    private record MouseBounds(int x, int y, int w, int h) {}
    private final List<Key> layout = new ArrayList<>();
    private List<Key> visible = List.of();
    private int x, y, width, height; private float scale; private int page;
    private boolean all,onlyEnabled; private String query = ""; private Set<String> enabled = Set.of();
    private String reserved="";
    private Function<String,List<Component>> tooltips=channel->List.of(WirelessControllerClient.name(channel));
    private static final float MOUSE_X=18.7F,MOUSE_Y=.25F,MOUSE_W=3F,MOUSE_H=4.9F,MOUSE_SPLIT=2.35F;
    private static final int MOUSE_OUTLINE=0xFF776246,MOUSE_RIM=0xFFB79B68,MOUSE_PALM=0xFFD9C6A1;
    ControllerInputPanel() {
        row(0, 0, new int[]{256,290,291,292,293,294,295,296,297,298,299,300,301,283,281,284});
        row(0, 1.3F, new int[]{96,49,50,51,52,53,54,55,56,57,48,45,61,259});
        row(0, 2.3F, new int[]{258,81,87,69,82,84,89,85,73,79,80,91,93,92});
        row(0, 3.3F, new int[]{280,65,83,68,70,71,72,74,75,76,59,39,257});
        row(0, 4.3F, new int[]{340,90,88,67,86,66,78,77,44,46,47,344});
        row(0, 5.3F, new int[]{341,343,342});
        layout.add(new Key(32,false,3,5.3F,5,1));
        row(8, 5.3F, new int[]{346,347,348,345});
        row(14.4F, 1.3F, new int[]{260,268,266}); row(14.4F, 2.3F, new int[]{261,269,267});
        layout.add(new Key(265,false,15.4F,4.3F,1,1)); row(14.4F,5.3F,new int[]{263,264,262});
        // Raised side buttons and the wheel win hit testing over the main buttons.
        layout.add(new Key(2,true,MOUSE_X+1.25F,MOUSE_Y+.7F,.5F,1.25F));
        layout.add(new Key(3,true,MOUSE_X-.35F,MOUSE_Y+1.7F,.5F,.75F));
        layout.add(new Key(4,true,MOUSE_X-.35F,MOUSE_Y+2.6F,.5F,.75F));
        layout.add(new Key(ControllerInput.SCROLL_UP,true,MOUSE_X+.35F,MOUSE_Y+3.2F,1F,.75F));
        layout.add(new Key(ControllerInput.SCROLL_DOWN,true,MOUSE_X+1.65F,MOUSE_Y+3.2F,1F,.75F));
        layout.add(new Key(0,true,MOUSE_X,MOUSE_Y,MOUSE_W/2,MOUSE_SPLIT));
        layout.add(new Key(1,true,MOUSE_X+MOUSE_W/2,MOUSE_Y,MOUSE_W/2,MOUSE_SPLIT));
        for(int code=5;code<8;code++)layout.add(new Key(code,true,MOUSE_X+(code-5)*1.05F,5.4F,.9F,.85F));
    }
    private void row(float x, float y, int[] codes) { for (int code : codes) layout.add(new Key(code,false,x++,y,1,1)); }
    void bounds(int x, int y, int width, int height) { this.x=x;this.y=y;this.width=width;this.height=height; }
    void enabled(Set<String> enabled) { this.enabled=enabled; }
    void reserved(String channel) { reserved=channel; }
    void tooltips(Function<String,List<Component>> tooltips) { this.tooltips=tooltips; }
    void query(String query) { this.query=query.toLowerCase(Locale.ROOT);page=0; }
    void toggle() { all=!all; page=0; }
    void toggleEnabled() { onlyEnabled=!onlyEnabled;page=0; }
    void page(int delta) { page=Math.max(0,page+delta); }
    private List<ControllerInput> filtered() {
        return ControllerInput.ALL.stream().filter(input->input.mouse()||(input.code()!=282&&(input.code()<320||input.code()>336)))
                .filter(input -> !onlyEnabled || enabled.contains(input.channel())).filter(input -> query.isEmpty()
                || WirelessControllerClient.name(input.channel()).getString().toLowerCase(Locale.ROOT).contains(query)
                || input.channel().contains(query)).toList();
    }
    private void rebuild() {
        if (listing()) {
            int cols=Math.max(3,width/74), rows=Math.max(1,height/19), count=cols*rows;
            List<ControllerInput> inputs=filtered(); page=Math.min(page, Math.max(0,(inputs.size()-1)/count));
            List<Key> keys=new ArrayList<>();
            for(int i=page*count;i<Math.min(inputs.size(),(page+1)*count);i++) {
                var input=inputs.get(i);int cell=i-page*count;
                keys.add(new Key(input.code(),input.mouse(),cell%cols*(float)width/cols,cell/cols*19F,(float)width/cols-3,17));
            }
            visible=keys;scale=1;
        } else { visible=layout;scale=Math.min(18F,Math.min(width/21.7F,height/6.65F)); }
    }
    private boolean listing() { return all||onlyEnabled||!query.isEmpty(); }
    String hit(double mx,double my) { return hit(mx,my,false); }
    String hit(double mx,double my,boolean includeReserved) {
        rebuild();
        for(Key key:visible) {
            if(!listing()&&key.mouse) {
                MouseBounds bounds=mouseBounds(key);
                if(mx<bounds.x||mx>=bounds.x+bounds.w||my<bounds.y||my>=bounds.y+bounds.h)continue;
                if(key.code<2) {
                    int left=x+Math.round(MOUSE_X*scale),top=y+Math.round(MOUSE_Y*scale);
                    int shellWidth=Math.round(MOUSE_W*scale),shellHeight=Math.round(MOUSE_H*scale);
                    int inset=(int)Math.ceil(mouseInset(Math.floor(my-top)+.5,shellWidth,shellHeight));
                    if(mx<left+inset||mx>=left+shellWidth-inset)continue;
                }
            } else {
                if(mx<x+key.x*scale||mx>=x+(key.x+key.w)*scale-2
                        ||my<y+key.y*scale||my>=y+(key.y+key.h)*scale-2)continue;
            }
            String channel=ControllerInput.channel(key.mouse,key.code);
            return !includeReserved&&channel.equals(reserved)?null:channel;
        }
        return null;
    }
    void render(GuiGraphics graphics,int mx,int my,String selected) {
        rebuild();var font=Minecraft.getInstance().font;
        for(Key key:visible) {
            if(!listing()&&key.mouse)continue;
            String channel=ControllerInput.channel(key.mouse,key.code);
            int l=x+Math.round(key.x*scale),t=y+Math.round(key.y*scale),w=Math.round(key.w*scale)-2;
            int h=Math.round(key.h*scale)-2;
            boolean active=enabled.contains(channel), chosen=channel.equals(selected);
            boolean locked=channel.equals(reserved);
            graphics.fill(l,t,l+w,t+h,locked?0xFF87847B:chosen?0xFFB8903D:active?0xFF55764A:0xFFBBA984);
            graphics.fill(l+1,t+1,l+w-1,t+h-1,locked?0xFFC7C3B7:chosen?0xFF635339:active?0xFFDFE3BB:0xFFECDDB9);
            Component full=channel.isEmpty()?Component.literal("Tab"):WirelessControllerClient.name(channel);
            String label=listing()?full.getString():compact(key);
            float textScale=Math.min(1F,(w-3F)/Math.max(1,font.width(label)));
            graphics.pose().pushPose();graphics.pose().translate(l+w/2F,t+h/2F,0);
            graphics.pose().scale(textScale,textScale,1);
            graphics.drawString(font,label,-font.width(label)/2,-4,locked?0xFF78766E:chosen?0xFFF4EBD5:active?0xFF34562D:0xFF85765D,false);
            graphics.pose().popPose();
        }
        if(!listing())renderMouse(graphics,selected);
        String hovered=hit(mx,my);
        if(hovered!=null&&!hovered.isEmpty()) graphics.renderTooltip(font,
                tooltips.apply(hovered).stream().map(Component::getVisualOrderText).toList(),mx,my);
    }
    private void renderMouse(GuiGraphics graphics,String selected) {
        int l=x+Math.round(MOUSE_X*scale),t=y+Math.round(MOUSE_Y*scale);
        int w=Math.round(MOUSE_W*scale),h=Math.round(MOUSE_H*scale),split=Math.round(MOUSE_SPLIT*scale),center=l+w/2;
        int centerRight=l+(w+1)/2;
        // A broad shell with rounded shoulders, a bevel and separate button faces.
        for(int row=0;row<h;row++) {
            int inset=(int)Math.ceil(mouseInset(row+.5,w,h));
            graphics.fill(l+inset,t+row,l+w-inset,t+row+1,MOUSE_OUTLINE);
            if(row==0||row==h-1)continue;
            int innerInset=(int)Math.ceil(mouseInset(row-.5,w-2,h-2));
            int rimLeft=l+1+innerInset,rimRight=l+w-1-innerInset;
            if(row<split) {
                graphics.fill(rimLeft,t+row,center,t+row+1,mouseEdge(0,selected));
                graphics.fill(center,t+row,rimRight,t+row+1,mouseEdge(1,selected));
            } else graphics.fill(rimLeft,t+row,rimRight,t+row+1,MOUSE_RIM);
            if(row<3||row>=h-3)continue;
            int faceInset=(int)Math.ceil(mouseInset(row-2.5,w-6,h-6));
            int faceLeft=l+3+faceInset,faceRight=l+w-3-faceInset;
            if(row<split-1) {
                graphics.fill(faceLeft,t+row,center-1,t+row+1,mouseColor(0,selected));
                graphics.fill(centerRight+1,t+row,faceRight,t+row+1,mouseColor(1,selected));
                graphics.fill(center-1,t+row,centerRight+1,t+row+1,MOUSE_OUTLINE);
            } else if(row>split+1) {
                graphics.fill(faceLeft,t+row,faceRight,t+row+1,MOUSE_PALM);
                graphics.fill(faceLeft,t+row,faceLeft+1,t+row+1,0xFFF2E0BA);
                graphics.fill(faceRight-1,t+row,faceRight,t+row+1,0xFFC1A77B);
            } else graphics.fill(faceLeft,t+row,faceRight,t+row+1,MOUSE_OUTLINE);
        }
        var font=Minecraft.getInstance().font;
        graphics.drawString(font,"L",l+w/4-font.width("L")/2,t+Math.round(1F*scale),mouseInk(0,selected),false);
        graphics.drawString(font,"R",l+w*3/4-font.width("R")/2,t+Math.round(1F*scale),mouseInk(1,selected),false);
        for(Key key:layout) {
            if(!key.mouse||key.code<2)continue;
            MouseBounds bounds=mouseBounds(key);
            int sx=bounds.x,sy=bounds.y,sw=bounds.w,sh=bounds.h;
            bevel(graphics,sx,sy,sw,sh,MOUSE_OUTLINE);
            bevel(graphics,sx+1,sy+1,sw-2,sh-2,mouseEdge(key.code,selected));
            bevel(graphics,sx+2,sy+2,sw-4,sh-4,mouseColor(key.code,selected));
            if(key.code==2) {
                for(int offset=4;offset<sh-3;offset+=4)graphics.fill(sx+2,sy+offset,sx+sw-2,sy+offset+1,mouseEdge(2,selected));
            } else if(key.code>=5) {
                String label=key.code==ControllerInput.SCROLL_UP?"↑":key.code==ControllerInput.SCROLL_DOWN?"↓":"M"+(key.code+1);float textScale=Math.min(1F,(sw-2F)/font.width(label));
                graphics.pose().pushPose();graphics.pose().translate(sx+sw/2F,sy+sh/2F,0);graphics.pose().scale(textScale,textScale,1);
                graphics.drawString(font,label,-font.width(label)/2,-4,mouseInk(key.code,selected),false);graphics.pose().popPose();
            }
        }
    }
    private MouseBounds mouseBounds(Key key) {
        int left=x+Math.round(MOUSE_X*scale),shellWidth=Math.round(MOUSE_W*scale);
        int sx=x+Math.round(key.x*scale),sy=y+Math.round(key.y*scale);
        int sw=Math.round(key.w*scale),sh=Math.round(key.h*scale);
        if(key.code==2) {
            // Match odd/even widths so both edges are equally far from the shell axis.
            if((sw&1)!=(shellWidth&1))sw++;
            sx=left+(shellWidth-sw)/2;
        } else if(key.code<2) {
            sx=key.code==0?left:left+shellWidth/2;
            sw=key.code==0?shellWidth/2:shellWidth-shellWidth/2;
        }
        return new MouseBounds(sx,sy,sw,sh);
    }
    private int mouseColor(int code,String selected) {
        String channel=ControllerInput.channel(true,code);
        if(channel.equals(reserved))return 0xFFC7C3B7;
        return channel.equals(selected)?0xFF635339:enabled.contains(channel)?0xFFDFE3BB:0xFFECDDB9;
    }
    private int mouseEdge(int code,String selected) {
        String channel=ControllerInput.channel(true,code);
        if(channel.equals(reserved))return 0xFF87847B;
        return channel.equals(selected)?0xFFB8903D:enabled.contains(channel)?0xFF55764A:MOUSE_RIM;
    }
    private int mouseInk(int code,String selected) {
        String channel=ControllerInput.channel(true,code);
        if(channel.equals(reserved))return 0xFF78766E;
        return channel.equals(selected)?0xFFF4EBD5:enabled.contains(channel)?0xFF34562D:0xFF85765D;
    }
    private static double mouseInset(double y,double w,double h) {
        double cap=y<h/2?w*.36:w*.48,edge=y<h/2?y:h-y;
        if(edge>=cap)return 0;
        return cap-Math.sqrt(Math.max(0,cap*cap-Math.pow(cap-Math.max(0,edge),2)));
    }
    private static void bevel(GuiGraphics graphics,int x,int y,int w,int h,int color) {
        if(w<=0||h<=0)return;
        graphics.fill(x+1,y,x+w-1,y+h,color);
        graphics.fill(x,y+1,x+w,y+h-1,color);
    }
    private static String compact(Key key) {
        if(key.mouse)return switch(key.code){case 0->"LMB";case 1->"RMB";case 2->"MMB";case ControllerInput.SCROLL_UP->"↑";case ControllerInput.SCROLL_DOWN->"↓";default->"M"+(key.code+1);};
        if(key.code>=290&&key.code<=314)return "F"+(key.code-289);
        if(key.code>=320&&key.code<=329)return Integer.toString(key.code-320);
        return switch(key.code) {
            case 256->"Esc";case 257->"Enter";case 258->"Tab";case 259->"⌫";
            case 260->"Ins";case 261->"Del";case 262->"→";case 263->"←";case 264->"↓";case 265->"↑";
            case 266->"PgUp";case 267->"PgDn";case 268->"Home";case 269->"End";case 280->"Caps";
            case 281->"ScrLk";case 282->"Num";case 283->"PrtSc";case 284->"Pause";
            case 340,344->"Shift";case 341,345->"Ctrl";case 342,346->"Alt";case 343,347->"Win";case 348->"Menu";
            case 32->"Space";case 330->".";case 331->"/";case 332->"*";case 333->"-";case 334->"+";case 335->"Enter";case 336->"=";
            default->WirelessControllerClient.name(ControllerInput.channel(false,key.code)).getString();
        };
    }
}
