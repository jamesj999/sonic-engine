package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.sprites.playable.SpriteRunningMode;

public class Tile {
   public byte[] groundHeights;
   public byte[] rightWallHeights;
   private byte angle;
   private boolean jumpThrough;

   public Tile(byte[] groundHeights, byte[] rightWallHeights, byte angle, boolean jumpThrough) {
      if (groundHeights.length == 16) {
         this.groundHeights = groundHeights;

      } else {
         //TODO: Proper exception handling
         System.out.println("A tile doesn't have the correct length rightWallHeights array...");
      }
      if (rightWallHeights.length == 16) {
         this.rightWallHeights = rightWallHeights;
      } else {
         //TODO: Proper exception handling
         System.out.println("A tile doesn't have the correct length rightWallHeights array...");
      }

      // TODO add angle recalculations - if the tile is flipped then the angle needs to be recalculated.
      this.angle = angle;
   }

   public Tile(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j, int k, int l, int m, int n, int o,
               int p, byte angle, boolean jumpThrough) {
      groundHeights = new byte[16];
      groundHeights[0] = (byte) a;
      groundHeights[1] = (byte) b;
      groundHeights[2] = (byte) c;
      groundHeights[3] = (byte) d;
      groundHeights[4] = (byte) e;
      groundHeights[5] = (byte) f;
      groundHeights[6] = (byte) g;
      groundHeights[7] = (byte) h;
      groundHeights[8] = (byte) i;
      groundHeights[9] = (byte) j;
      groundHeights[10] = (byte) k;
      groundHeights[11] = (byte) l;
      groundHeights[12] = (byte) m;
      groundHeights[13] = (byte) n;
      groundHeights[14] = (byte) o;
      groundHeights[15] = (byte) p;

      this.angle = angle;
      this.jumpThrough = jumpThrough;

   }

   /**
    * Shorthand to create a tile without having to create the height map
    * separately. a-p are ground heights and a1-p1 are right wall heights.
    */
   public Tile(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j, int k, int l, int m, int n, int o,
               int p, int a1, int b1, int c1, int d1, int e1, int f1, int g1,int h1, int i1, int j1, int k1, int l1,
               int m1, int n1, int o1, int p1, byte angle, boolean jumpThrough) {
      groundHeights = new byte[16];
      groundHeights[0] = (byte) a;
      groundHeights[1] = (byte) b;
      groundHeights[2] = (byte) c;
      groundHeights[3] = (byte) d;
      groundHeights[4] = (byte) e;
      groundHeights[5] = (byte) f;
      groundHeights[6] = (byte) g;
      groundHeights[7] = (byte) h;
      groundHeights[8] = (byte) i;
      groundHeights[9] = (byte) j;
      groundHeights[10] = (byte) k;
      groundHeights[11] = (byte) l;
      groundHeights[12] = (byte) m;
      groundHeights[13] = (byte) n;
      groundHeights[14] = (byte) o;
      groundHeights[15] = (byte) p;

      rightWallHeights = new byte[16];
      rightWallHeights[0] = (byte) a1;
      rightWallHeights[1] = (byte) b1;
      rightWallHeights[2] = (byte) c1;
      rightWallHeights[3] = (byte) d1;
      rightWallHeights[4] = (byte) e1;
      rightWallHeights[5] = (byte) f1;
      rightWallHeights[6] = (byte) g1;
      rightWallHeights[7] = (byte) h1;
      rightWallHeights[8] = (byte) i1;
      rightWallHeights[9] = (byte) j1;
      rightWallHeights[10] = (byte) k1;
      rightWallHeights[11] = (byte) l1;
      rightWallHeights[12] = (byte) m1;
      rightWallHeights[13] = (byte) n1;
      rightWallHeights[14] = (byte) o1;
      rightWallHeights[15] = (byte) p1;

      this.angle = angle;
      this.jumpThrough = jumpThrough;
   }

   public byte getHeightAt(byte y, SpriteRunningMode spriteRunningMode) {
      switch(spriteRunningMode) {
         case GROUND:
            return groundHeights[y];
         case RIGHTWALL:
            return rightWallHeights[y];
         case CEILING:
            return (byte) (16 - groundHeights[y]);
         case LEFTWALL:
            return (byte) (16 - rightWallHeights[y]);
      }
      //TODO: Proper exception handling.
      System.out.println("No Sprite Running Mode provided to Tile for height calculation...");
      return -1;
   }

   public byte getAngle() {
      return angle;
   }

//   public Tile copy(boolean flipX, boolean flipY) {
//      return new Tile(groundHeights, rightWallHeights, angle, flipX, flipY);
//   }

   public boolean getJumpThrough() {
      return jumpThrough;
   }

}
