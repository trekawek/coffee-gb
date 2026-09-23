package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.joypad.Button;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Crops/scales generated artwork, registers it to the skin, and assembles reusable states. */
public class PreparePressedControls {
    private static Path root, design, resources;
    private static final Map<String, BufferedImage> sources = new HashMap<>();
    private static final String[] NAMES = {"a", "b", "a_b", "select", "start", "up", "up_right",
            "right", "down_right", "down", "down_left", "left", "up_left"};
    private static final Button[][] BUTTONS = {{Button.A}, {Button.B}, {Button.A, Button.B},
            {Button.SELECT}, {Button.START}, {Button.UP}, {Button.UP, Button.RIGHT}, {Button.RIGHT},
            {Button.DOWN, Button.RIGHT}, {Button.DOWN}, {Button.DOWN, Button.LEFT}, {Button.LEFT},
            {Button.UP, Button.LEFT}};

    public static void main(String[] args) throws Exception {
        root=Path.of(args[0]);
        design=root.resolve("android/design/pressed-controls");
        resources=root.resolve("android/app/src/main/res/drawable-nodpi");
        for (String name : new String[]{"a", "b", "utility", "bridge", "dpad"}) {
            sources.put(name, trim(ImageIO.read(design.resolve("source/"+name+".png").toFile())));
        }
        for (boolean portrait : new boolean[]{true, false}) prepare(portrait);
    }

    private static void prepare(boolean portrait) throws Exception {
        String orientation=portrait?"portrait":"landscape";
        var skin=ImageIO.read(resources.resolve("coffee_gb_skin_"+orientation+".png").toFile());
        var neutralCue=ImageIO.read(resources.resolve("coffee_gb_action_bridge_cue.png").toFile());
        var neutral=copy(skin);
        draw(neutral, neutralCue, TouchControlsLayout.actionBridgeCueBounds(skin.getWidth(),skin.getHeight()));
        var atlas=new BufferedImage(PressedControlLayout.ATLAS_WIDTH, PressedControlLayout.ATLAS_HEIGHT,
                BufferedImage.TYPE_INT_ARGB);
        for (PressedControlLayout control : PressedControlLayout.values()) {
            var bounds=control.bounds(skin.getWidth(),skin.getHeight());
            int width=control.spriteWidth(portrait), height=control.spriteHeight(portrait);
            var sprite=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
            var g=sprite.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int padding=control==PressedControlLayout.BRIDGE?1:0;
            g.drawImage(sources.get(control.sourceName),padding,padding,width-2*padding,height-2*padding,null);
            g.dispose();
            if (control!=PressedControlLayout.BRIDGE) {
                for(int y=0;y<height;y++) {
                    int first=width,last=-1;
                    for(int x=0;x<width;x++) {
                        int rgb=skin.getRGB((int)bounds.left()+x,(int)bounds.top()+y);
                        if (Math.max((rgb>>16)&255,Math.max((rgb>>8)&255,rgb&255))<145) {
                            first=Math.min(first,x); last=Math.max(last,x);
                        }
                    }
                    for(int x=0;x<width;x++) {
                        int rgba=sprite.getRGB(x,y);
                        double opacity=x<first||x>last?0:1;
                        // Disjoint directional sectors prevent a horizontal press from tinting
                        // the side edges of the vertical arms (and vice versa).
                        double dx=x-(width-1)/2.0,dy=y-(height-1)/2.0;
                        boolean selected=switch(control) {
                            case UP -> dy<0 && -dy>=Math.abs(dx);
                            case DOWN -> dy>=0 && dy>=Math.abs(dx);
                            case LEFT -> dx<0 && -dx>Math.abs(dy);
                            case RIGHT -> dx>=0 && dx>Math.abs(dy);
                            default -> true;
                        };
                        double fade=!selected?0:control.sourceName.equals("dpad")
                                ?(Math.hypot(dx,dy)-.12*width)/(.03*width):1;
                        // The A+B cue sits above the two action buttons even when only one is lit.
                        if(control==PressedControlLayout.A || control==PressedControlLayout.B) {
                            var cueBounds=TouchControlsLayout.actionBridgeCueBounds(skin.getWidth(),skin.getHeight());
                            int cueX=(int)Math.floor((bounds.left()+x-cueBounds.left())
                                    *neutralCue.getWidth()/(cueBounds.right()-cueBounds.left()));
                            int cueY=(int)Math.floor((bounds.top()+y-cueBounds.top())
                                    *neutralCue.getHeight()/(cueBounds.bottom()-cueBounds.top()));
                            if(cueX>=0 && cueX<neutralCue.getWidth() && cueY>=0 && cueY<neutralCue.getHeight()) {
                                opacity*=1-(neutralCue.getRGB(cueX,cueY)>>>24)/255.0;
                            }
                        }
                        int alpha=(int)Math.round((rgba>>>24)*opacity*Math.max(0,Math.min(1,fade)));
                        sprite.setRGB(x,y,(rgba&0xffffff)|(alpha<<24));
                    }
                }
            }
            var atlasGraphics=atlas.createGraphics();
            atlasGraphics.drawImage(sprite,control.atlasX,control.atlasY,null);
            atlasGraphics.dispose();
        }
        ImageIO.write(atlas,"png",resources.resolve("coffee_gb_pressed_"+orientation+".png").toFile());
        var sheet=new BufferedImage(1280,1360,BufferedImage.TYPE_INT_RGB);
        var sheetGraphics=sheet.createGraphics();
        sheetGraphics.setColor(new Color(244,239,232));
        sheetGraphics.fillRect(0,0,1280,1360);
        sheetGraphics.setFont(new Font("SansSerif",Font.BOLD,20));
        for(int i=0;i<NAMES.length;i++) {
            int mask=0;
            for(Button button:BUTTONS[i]) mask|=TouchPressState.bit(button);
            var composed=compose(neutral,atlas,portrait,mask);
            int[] crop=previewCrop(i,portrait);
            var state=new BufferedImage(crop[2],crop[3],BufferedImage.TYPE_INT_ARGB);
            var g=state.createGraphics();
            g.drawImage(composed,0,0,crop[2],crop[3],crop[0],crop[1],crop[0]+crop[2],crop[1]+crop[3],null);
            g.dispose();
            ImageIO.write(state,"png",design.resolve("states/"+orientation+"/"+NAMES[i]+".png").toFile());
            int cellX=(i%4)*320, cellY=(i/4)*340;
            sheetGraphics.setColor(new Color(65,45,39));
            sheetGraphics.drawString(NAMES[i].replace('_','+').toUpperCase(),cellX+20,cellY+29);
            float scale=Math.min(292f/state.getWidth(),280f/state.getHeight());
            int dw=Math.round(state.getWidth()*scale),dh=Math.round(state.getHeight()*scale);
            sheetGraphics.drawImage(state,cellX+(320-dw)/2,cellY+48+(280-dh)/2,dw,dh,null);
        }
        sheetGraphics.dispose();
        ImageIO.write(sheet,"png",design.resolve(orientation+"-states.png").toFile());
        var mixed=compose(neutral,atlas,portrait,TouchPressState.bit(Button.UP)|TouchPressState.bit(Button.LEFT)
                |TouchPressState.bit(Button.A)|TouchPressState.bit(Button.B));
        var preview=new BufferedImage(portrait?411:860,portrait?731:484,BufferedImage.TYPE_INT_RGB);
        var g=preview.createGraphics();
        g.setColor(new Color(40,43,32)); g.fillRect(0,0,preview.getWidth(),preview.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(mixed,0,0,preview.getWidth(),preview.getHeight(),null);g.dispose();
        ImageIO.write(preview,"png",design.resolve(orientation+"-preview.png").toFile());
        System.out.println(orientation+": atlas, 13 states and phone preview saved");
    }

    private static BufferedImage compose(BufferedImage neutral, BufferedImage atlas, boolean portrait, int mask) {
        var result=copy(neutral);
        for(PressedControlLayout control:PressedControlLayout.values()) {
            if(!control.active(mask))continue;
            var sprite=atlas.getSubimage(control.atlasX,control.atlasY,
                    control.spriteWidth(portrait),control.spriteHeight(portrait));
            draw(result,sprite,control.bounds(neutral.getWidth(),neutral.getHeight()));
        }
        return result;
    }

    private static int[] previewCrop(int state,boolean portrait) {
        if(state<3)return portrait?new int[]{584,1060,312,238}:new int[]{1350,356,300,246};
        if(state<5)return portrait?new int[]{290,1354,320,90}:new int[]{state==3?106:1430,778,140,82};
        return portrait?new int[]{48,1008,296,300}:new int[]{36,331,282,286};
    }

    private static void draw(BufferedImage target,BufferedImage sprite,SkinTransform.Bounds bounds) {
        var g=target.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        var transform=AffineTransform.getTranslateInstance(bounds.left(),bounds.top());
        transform.scale((bounds.right()-bounds.left())/sprite.getWidth(),
                (bounds.bottom()-bounds.top())/sprite.getHeight());
        g.drawImage(sprite,transform,null);g.dispose();
    }

    private static BufferedImage copy(BufferedImage source) {
        var result=new BufferedImage(source.getWidth(),source.getHeight(),BufferedImage.TYPE_INT_ARGB);
        var g=result.createGraphics();g.drawImage(source,0,0,null);g.dispose();return result;
    }

    private static BufferedImage trim(BufferedImage source) {
        if(!source.getColorModel().hasAlpha())throw new IllegalArgumentException("Sprite has no alpha");
        int left=source.getWidth(),top=source.getHeight(),right=0,bottom=0,transparent=0;
        for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++) {
            int alpha=source.getRGB(x,y)>>>24;
            if(alpha==0)transparent++;
            if(alpha>8){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+1);bottom=Math.max(bottom,y+1);}
        }
        if(transparent==0)throw new IllegalArgumentException("Sprite has no transparent background");
        System.out.printf("Trim %dx%d to [%d,%d,%d,%d]%n",source.getWidth(),source.getHeight(),left,top,right,bottom);
        return source.getSubimage(left,top,right-left,bottom-top);
    }
}
