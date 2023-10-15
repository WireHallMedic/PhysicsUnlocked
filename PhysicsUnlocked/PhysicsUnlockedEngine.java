/*
   The primary work object for this library.
   All distances are in tiles.
   All speeds are in tiles per second.
   All accelerations are in tiles per second per second.
   
   The engine does its work in its own thread, as fast as it can. As our time precision is milliseconds,
   this is limited to a thoeretical maximum of 1000 cycles per second.
*/

package PhysicsUnlocked;

import java.util.*;
import java.awt.*;

public class PhysicsUnlockedEngine implements Runnable
{
   public static final int PLAYER = 1;
   public static final int PLAYER_PROJECTILE = 2;
   public static final int ENEMY = 3;
   public static final int ENEMY_PROJECTILE = 4;
   public static final int ENVIRONMENT = 5;
   
	private double gravity;
	private double terminalVelocity;
   private Vector<MovingBoundingObject> objList;               // full list of all objects
   private Vector<MovingBoundingObject> playerList;            // collides with geometry, enemies, enemy projectiles
   private Vector<MovingBoundingObject> playerProjectileList;  // collides with geometry, enemies
   private Vector<MovingBoundingObject> enemyList;             // collides with geometry, players, player projectiles
   private Vector<MovingBoundingObject> enemyProjectileList;   // collides with geometry, players
   private Vector<MovingBoundingObject> environmentList;       // collides with everything including other entries on this list
   private boolean runFlag;
   private boolean terminateFlag;
   private Thread thread;
   private int cps;
   private boolean[][] geometry;


	public double getGravity(){return gravity;}
	public double getTerminalVelocity(){return terminalVelocity;}
   public Vector<MovingBoundingObject> getObjList(){return objList;}
   public boolean getRunFlag(){return runFlag;}
   public int getCPS(){return cps;}
   public boolean[][] getGeometry(){return geometry;}


	public void setGravity(double g){gravity = g;}
	public void setTerminalVelocity(double t){terminalVelocity = t;}
   public void setObjList(Vector<MovingBoundingObject> newList){objList = newList;}
   public void setRunFlag(boolean rf){runFlag = rf;}
   public void setGeometry(boolean[][] g){geometry = g;}
   public void terminate(){terminateFlag = true;}           // mainly for testing

   // standard constructor
   public PhysicsUnlockedEngine()
   {
      objList = new Vector<MovingBoundingObject>();
      playerList = new Vector<MovingBoundingObject>();
      playerProjectileList = new Vector<MovingBoundingObject>();
      enemyList = new Vector<MovingBoundingObject>();
      enemyProjectileList = new Vector<MovingBoundingObject>();
      environmentList = new Vector<MovingBoundingObject>();
      runFlag = false;
      geometry = new boolean[1][1];
      thread = new Thread(this);
      thread.start();
   }
   
   // add a moving object
   public void add(MovingBoundingObject obj){add(obj, -1);}
   public void add(MovingBoundingObject obj, int list)
   {
      objList.add(obj);
      switch(list)
      {
         case PLAYER : playerList.add(obj); break;
         case PLAYER_PROJECTILE : playerProjectileList.add(obj); break;
         case ENEMY : enemyList.add(obj); break;
         case ENEMY_PROJECTILE : enemyProjectileList.add(obj); break;
         case ENVIRONMENT : environmentList.add(obj); break;
      }
   }
   
   // remove a moving object
   public void remove(MovingBoundingObject obj)
   {
      objList.remove(obj);
      playerList.remove(obj);
      playerProjectileList.remove(obj);
      enemyList.remove(obj);
      enemyProjectileList.remove(obj);
      environmentList.remove(obj);
   }
   
   // called by thread.start()
   public void run()
   {
      long lastTime = System.currentTimeMillis();
      long curTime = lastTime;
      long lastSecond = lastTime;      // used for calculating cycles per second
      int cyclesThisSecond = 0;
      int millisElapsed = 0;
      while(!terminateFlag)
      {
         curTime = System.currentTimeMillis();
         if(runFlag)
         {
            millisElapsed = (int)(curTime - lastTime);   // milliseconds since last execution
            if(millisElapsed > 0)  // no need to waste cycles if there will be no change
            {
               doPhysics(millisElapsed);
               doCollisionChecks();
               cyclesThisSecond++;
               
               // it's been a second, update cycles per second
               if(curTime - lastSecond >= 1000)
               {
                  cps = cyclesThisSecond;
                  cyclesThisSecond = 0;
                  lastSecond = curTime;
               }
            }
         }
         // notate for next loop
         lastTime = curTime;
         // let someone else have a turn
         Thread.yield();   
      }
   }
   
   // perform physics on each object
   public void doPhysics(int millisElapsed)
   {
      double secondsElapsed = (double)millisElapsed / 1000.0;
      for(MovingBoundingObject obj : objList)
      {
         // aquire the lock so that nothing is adjusted mid-processing
         synchronized(obj)
         {
            // apply any accelerations the object has. This may include deceleration (ie friction).
            obj.applyAccelerations(secondsElapsed);
            // apply gravity, if the object is affected by gravity
            if(obj.isAffectedByGravity())
               obj.applyGravityImpulse(convertAccelerationToImpulse(getGravity(), secondsElapsed), getTerminalVelocity());
            // if the object is pushed by geometry, adjust its speeds to stop short of collision
            if(obj.isPushedByGeometry())
            {
               // prevent weirdness by setting movement against adjacent surfaces to 0.
               bindMovement(obj);
               // generate culled list of potential geometric collisions and test
               Vector<DoublePair> prospectList = new Vector<DoublePair>();
               DoublePair origin = obj.getPotentialCollisionOrigin();
               DoublePair end = obj.getPotentialCollisionEnd();
               for(int x = (int)origin.x; x <= (int)end.x; x++)
               for(int y = (int)origin.y; y <= (int)end.y; y++)
               {
                  if(isInBounds(x, y) && geometry[x][y])
                     prospectList.add(new DoublePair((double)x, (double)y));
               }
               // sort list
               prospectList = getOrderedList(prospectList, obj.getLoc());
               // resolve each potential collision in order
               for(DoublePair dPair : prospectList)   
               {
                  SweptAABB newCollision = new SweptAABB(obj, secondsElapsed, (int)dPair.x, (int)dPair.y);
                  if(newCollision.isCollision())
                  {
                     obj.adjustForCollision(newCollision);
                  }
               }
            }
            // move object
            obj.applySpeeds(secondsElapsed); 
         }
      }
   }
   
   // perform non-push collision checks
   public void doCollisionChecks()
   {
      // player collisions
      for(MovingBoundingObject obj : playerList)
      {
         // aquire the lock so that nothing is adjusted mid-processing
         synchronized(obj)
         {
            // things not pushed by geometery can collide with it
            if(!obj.isPushedByGeometry() && isCollidingWithGeometry(obj))
               obj.movingCollisionOccured(new MovingCollision(obj, null));
            // test against enemies
            for(MovingBoundingObject enemy : enemyList)
            {
               synchronized(enemy)
               {
                  if(obj.isColliding(enemy))
                  {
                     obj.movingCollisionOccured(new MovingCollision(obj, enemy));
                     enemy.movingCollisionOccured(new MovingCollision(enemy, obj));
                  }
               } // release enemy lock
            }
            // test against enemy projectiles
            for(MovingBoundingObject enemyProj : enemyProjectileList)
            {
               synchronized(enemyProj)
               {
                  if(obj.isColliding(enemyProj))
                  {
                     obj.movingCollisionOccured(new MovingCollision(obj, enemyProj));
                     enemyProj.movingCollisionOccured(new MovingCollision(enemyProj, obj));
                  }
               } // release enemy projectile lock
            }
         } // release player lock
      } // end player collisions
      
      // test enemies. We have already caught any player-enemy collisions.
      for(MovingBoundingObject obj : enemyList)
      {
         // aquire the lock so that nothing is adjusted mid-processing
         synchronized(obj)
         {
            // things not pushed by geometery can collide with it
            if(!obj.isPushedByGeometry() && isCollidingWithGeometry(obj))
               obj.movingCollisionOccured(new MovingCollision(obj, null));
            // test against player projectiles
            for(MovingBoundingObject playerProj : playerProjectileList)
            {
               synchronized(playerProj)
               {
                  if(obj.isColliding(playerProj))
                  {
                     obj.movingCollisionOccured(new MovingCollision(obj, playerProj));
                     playerProj.movingCollisionOccured(new MovingCollision(playerProj, obj));
                  }
               } // release player projectile lock
            }
         } // release enemy lock
      } // end enemy collisions
      
      // since we've caught all player and enemy collisions, projectiles just need to test for geometry
      for(MovingBoundingObject obj : playerProjectileList)
      {
         synchronized(obj)
         {
            if(!obj.isPushedByGeometry() && isCollidingWithGeometry(obj))
               obj.movingCollisionOccured(new MovingCollision(obj, null));
         }
      }
      for(MovingBoundingObject obj : enemyProjectileList)
      {
         synchronized(obj)
         {
            if(!obj.isPushedByGeometry() && isCollidingWithGeometry(obj))
               obj.movingCollisionOccured(new MovingCollision(obj, null));
         }
      }
      
      // since environmental object collide with everything, we'll just do all that here
      for(int i = 0; i < environmentList.size(); i++)
      {
         MovingBoundingObject obj = environmentList.elementAt(i);
         // aquire the lock so that nothing is adjusted mid-processing
         synchronized(obj)
         {
            // things not pushed by geometery can collide with it
            if(!obj.isPushedByGeometry() && isCollidingWithGeometry(obj))
               obj.movingCollisionOccured(new MovingCollision(obj, null));
            // test against everything
            for(MovingBoundingObject otherObj : objList)
            {
               // well, everything except itself
               if(obj != otherObj)
               {
                  // aquire lock for other object
                  synchronized(otherObj)
                  {
                     if(obj.isColliding(otherObj))
                     {
                        obj.movingCollisionOccured(new MovingCollision(obj, otherObj));
                        // don't add reciprocal event for other environmental objs, as they will make their own
                        if(!environmentList.contains(otherObj))
                           otherObj.movingCollisionOccured(new MovingCollision(otherObj, obj));
                     }
                  } // release otherObj lock
               }
            }
         } // release environment lock
      } // end environment collisions
   }
   
   // cut down acceleration to an impulse
   public double convertAccelerationToImpulse(double accl, double secondsElapsed)
   {
      return accl * secondsElapsed;
   }
   
   // prevent pressing against surfaces; this prevents artifacts
   public void bindMovement(MovingBoundingObject obj)
   {
      if(obj.getXSpeed() > 0.0 && touchingRightWall(obj))
         obj.setXSpeed(0.0);
      else if(obj.getXSpeed() < 0.0 && touchingLeftWall(obj))
         obj.setXSpeed(0.0);
      if(obj.getYSpeed() > 0.0 && touchingFloor(obj))
         obj.setYSpeed(0.0);
      else if(obj.getYSpeed() < 0.0 && touchingCeiling(obj))
         obj.setYSpeed(0.0);
   }
   
   // is it touching in the Y+ direction?
   public boolean touchingFloor(MovingBoundingObject obj)
   {
      int startX = (int)(obj.getXLoc() - obj.getHalfWidth());
      int endX = (int)(obj.getXLoc() + obj.getHalfWidth());
      for(int xBlock = startX; xBlock <= endX; xBlock++)
      {
         if(collisionCheckGeometry(xBlock, (int)(obj.getYLoc() + obj.getHeight()), obj, 0.0, 0.01))
            return true;
      }
      return false;
   }
   
   // is it touching in the Y- direction?
   public boolean touchingCeiling(MovingBoundingObject obj)
   {
      int startX = (int)(obj.getXLoc() - obj.getHalfWidth());
      int endX = (int)(obj.getXLoc() + obj.getHalfWidth());
      for(int xBlock = startX; xBlock <= endX; xBlock++)
      {
         if(collisionCheckGeometry(xBlock, (int)(obj.getYLoc() - obj.getHeight()), obj, 0.0, -0.01))
            return true;
      }
      return false;
   }
   
   // is it touching in the X- direction?
   public boolean touchingLeftWall(MovingBoundingObject obj)
   {
      int startY = (int)(obj.getYLoc() - obj.getHalfHeight());
      int endY = (int)(obj.getYLoc() + obj.getHalfHeight());
      for(int yBlock = startY; yBlock <= endY; yBlock++)
      {
         if(collisionCheckGeometry((int)(obj.getXLoc() - obj.getWidth()), yBlock, obj, -0.01, 0.0))
            return true;
      }
      return false;
   }
   
   // is it touching in the X+ direction?
   public boolean touchingRightWall(MovingBoundingObject obj)
   {
      int startY = (int)(obj.getYLoc() - obj.getHalfHeight());
      int endY = (int)(obj.getYLoc() + obj.getHalfHeight());
      for(int yBlock = startY; yBlock <= endY; yBlock++)
      {
         if(collisionCheckGeometry((int)(obj.getXLoc() + obj.getWidth()), yBlock, obj, 0.01, 0.0))
            return true;
      }
      return false;
   }
   
   // returns a pair of normals, checking one tile up, down, left, right
   public DoublePair getOrthoGeometryCollisionNormals(MovingBoundingObject obj)
   {
      DoublePair bump = new DoublePair(0.0, 0.0);
      if(collisionCheckGeometry((int)obj.getXLoc(), (int)(obj.getYLoc() - obj.getHalfHeight()), obj, 0.0, -0.01))
         bump.y = 1.0;
      if(collisionCheckGeometry((int)obj.getXLoc(), (int)(obj.getYLoc() + obj.getHalfHeight()), obj, 0.0, 0.01))
         bump.y = -1.0;
      if(collisionCheckGeometry((int)(obj.getXLoc() - obj.getHalfWidth()), (int)obj.getYLoc(), obj, -0.01, 0.0))
         bump.x = 1.0;
      if(collisionCheckGeometry((int)(obj.getXLoc() + obj.getHalfWidth()), (int)obj.getYLoc(), obj, 0.01, 0.0))
         bump.x = -1.0;
      return bump;
   }
   
   // is overlapping with any geometry?
   public boolean isCollidingWithGeometry(MovingBoundingObject obj)
   {
      int startX = (int)(obj.getXLoc() - obj.getHalfWidth());
      int endX = (int)(obj.getXLoc() + obj.getHalfWidth());
      int startY = (int)(obj.getYLoc() - obj.getHalfHeight());
      int endY = (int)(obj.getYLoc() + obj.getHalfHeight());
      for(int xBlock = startX; xBlock <= endX; xBlock++)
      for(int yBlock = startY; yBlock <= endY; yBlock++)
      {
         if(collisionCheckGeometry(xBlock, yBlock, obj, 0.0, 0.0))
            return true;
      }
      return false;
   }
   
   // is the passed location in the array?
   public boolean isInBounds(int x, int y)
   {
      return x >= 0 && x < geometry.length &&
             y >= 0 && y < geometry[0].length;
   }
   
   // check if colliding with a particular tile
   private boolean collisionCheckGeometry(int x, int y, MovingBoundingObject obj){return collisionCheckGeometry(x, y, obj, 0.0, 0.0);}
   private boolean collisionCheckGeometry(int x, int y, MovingBoundingObject obj, double xShift, double yShift)
   {
      if(isInBounds(x, y))
      {
         if(geometry[x][y])
         {
            double minX = x - obj.getHalfWidth();
            double minY = y - obj.getHalfHeight();
            double maxX = x + 1.0 + obj.getHalfWidth();
            double maxY = y + 1.0 + obj.getHalfHeight();
            return obj.getXLoc() + xShift <= maxX &&
                   obj.getXLoc() + xShift >= minX &&
                   obj.getYLoc() + yShift <= maxY &&
                   obj.getYLoc() + yShift >= minY;
         }
         return false;
      }
      return true; // OOB is always a collision
   }
   
   // order list by closest
   private Vector<DoublePair> getOrderedList(Vector<DoublePair> list, DoublePair loc)
   {
      Vector<DoublePair> newList = new Vector<DoublePair>();
      while(list.size() > 0)
      {
         double bestDist = 10000.0;
         int index = -1;
         for(int i = 0; i < list.size(); i++)
         {
            double distMetric = getDistanceMetric(list.elementAt(i), loc);
            if(distMetric < bestDist)
            {
               index = i;
               bestDist = distMetric;
            }
         }
         newList.add(list.elementAt(index));
         list.removeElementAt(index);
      }
      return newList;
   }
   
   // return metric for comparing distances. This is the Pythagorean theory without taking the square root
   private double getDistanceMetric(DoublePair boxLoc, DoublePair loc)
   {
      double a = boxLoc.x + .5 - loc.x;
      double b = boxLoc.y + .5 - loc.y;
      return (a * a) + (b * b);
   }
}