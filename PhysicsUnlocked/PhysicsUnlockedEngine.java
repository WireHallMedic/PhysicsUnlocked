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
import java.awt.event.*;

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
   private GeometryType[][] geometry;
   private Vector<MovingBoundingObject> removalList;
   private long lastTime = System.currentTimeMillis();
   private long curTime = lastTime;
   private long lastSecond = lastTime;      // used for calculating cycles per second
   private int cyclesThisSecond = 0;
   private int millisElapsed = 0;


	public double getGravity(){return gravity;}
	public double getTerminalVelocity(){return terminalVelocity;}
   public Vector<MovingBoundingObject> getObjList(){return objList;}
   public boolean getRunFlag(){return runFlag;}
   public int getCPS(){return cps;}
   public GeometryType[][] getGeometry(){return geometry;}


	public void setGravity(double g){gravity = g;}
	public void setTerminalVelocity(double t){terminalVelocity = t;}
   public void setObjList(Vector<MovingBoundingObject> newList){objList = newList;}
   public void setRunFlag(boolean rf){runFlag = rf;}
   public void setGeometry(GeometryType[][] g){geometry = g;}
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
      removalList = new Vector<MovingBoundingObject>();
      runFlag = false;
      geometry = new GeometryType[1][1];
      geometry[0][0] = GeometryType.EMPTY;
      thread = new Thread(this);
      thread.start();
   }
   
   public void runUnthreaded()
   {
      terminate();
   }
   
   // add a moving object
   public void add(MovingBoundingObject obj){add(obj, ENVIRONMENT);}
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
   
   // note a moving object as ready for removal
   public void remove(MovingBoundingObject obj)
   {
      removalList.add(obj);
   }
   
   private void clearRemovalList()
   {
      synchronized(removalList)
      {
         for(MovingBoundingObject obj : removalList)
         {
            objList.remove(obj);
            playerList.remove(obj);
            playerProjectileList.remove(obj);
            enemyList.remove(obj);
            enemyProjectileList.remove(obj);
            environmentList.remove(obj);
         }
         removalList.clear();
      }
   }
   
   public void doLoop()
   {
      if(lastTime == 0)
      {
         lastTime = System.currentTimeMillis();
         curTime = lastTime;
         lastSecond = lastTime;      // used for calculating cycles per second
         cyclesThisSecond = 0;
         millisElapsed = 0;
      }
      curTime = System.currentTimeMillis();
      if(runFlag)
      {
         millisElapsed = (int)(curTime - lastTime);   // milliseconds since last execution
         if(millisElapsed > 0)  // no need to waste cycles if there will be no change
         {
            synchronized(this)
            {
               doPhysics(millisElapsed);
               doCollisionChecks();
            }
            cyclesThisSecond++;
            
            // it's been a second, update cycles per second
            if(curTime - lastSecond >= 1000)
            {
               cps = cyclesThisSecond;
               cyclesThisSecond = 0;
               lastSecond = curTime;
            }
         }
         clearRemovalList();
      }
      // notate for next loop
      lastTime = curTime;
   }
   
   // called by thread.start()
   public void run()
   {
      lastTime = System.currentTimeMillis();
      curTime = lastTime;
      lastSecond = lastTime;      // used for calculating cycles per second
      cyclesThisSecond = 0;
      millisElapsed = 0;
      while(!terminateFlag)
      {
         doLoop();
         Thread.yield();   // let someone else have a turn
      }
   }
   
   // perform physics on each object
   public void doPhysics(int millisElapsed)
   {
      double secondsElapsed = (double)millisElapsed / 1000.0;
      // aquire the lock so that nothing is adjusted mid-processing
      synchronized(objList)
      {
         for(MovingBoundingObject obj : objList)
         {
            // apply any accelerations the object has. This may include deceleration (ie friction).
            obj.applyAccelerations(secondsElapsed);
            // apply gravity, if the object is affected by gravity
            if(obj.isAffectedByGravity())
               obj.applyGravityImpulse(convertAccelerationToImpulse(getGravity(), secondsElapsed), getTerminalVelocity());
            // if the object is pushed by geometry, adjust its speeds to stop short of collision
            if(obj.isPushedByGeometry())
            {
               // generate culled list of potential geometric collisions and test
               Vector<DoublePair> prospectList = new Vector<DoublePair>();
               int[] origin = obj.getPotentialCollisionOrigin(secondsElapsed);
               int[] end = obj.getPotentialCollisionEnd(secondsElapsed);
               for(int x = origin[0]; x <= end[0]; x++)
               for(int y = origin[1]; y <= end[1]; y++)
               {
                  if(isInBounds(x, y))
                  {
                     if(geometry[x][y] != GeometryType.EMPTY)
                        prospectList.add(new DoublePair((double)x, (double)y));
                  }
                  else
                  {
                     prospectList.add(new DoublePair((double)x, (double)y));
                  }
               }
               // sort list
               prospectList = orderByClosest(prospectList, obj.getLoc());
               // resolve each potential collision in order
               for(DoublePair dPair : prospectList)   
               {
                  GeometryType geoType = getGeometryType((int)dPair.x, (int)dPair.y);
                  SweptAABB newCollision = new SweptAABB(obj, secondsElapsed, (int)dPair.x, (int)dPair.y, geoType);
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
      // aquire the lock so that nothing is adjusted mid-processing
      synchronized(playerList)
      {
         // player collisions
         for(MovingBoundingObject obj : playerList)
         {
            // things not pushed by geometery can collide with it
            if(!obj.isPushedByGeometry() && isCollidingWithGeometry(obj))
               obj.movingCollisionOccured(new MovingCollision(obj, null));
            // test against enemies
            synchronized(enemyList)
            {
               for(MovingBoundingObject enemy : enemyList)
               {
                  if(obj.isColliding(enemy))
                  {
                     obj.movingCollisionOccured(new MovingCollision(obj, enemy));
                     enemy.movingCollisionOccured(new MovingCollision(enemy, obj));
                  }
               }
            }
            synchronized(enemyProjectileList)
            {
               // test against enemy projectiles
               for(MovingBoundingObject enemyProj : enemyProjectileList)
               {
                  if(obj.isColliding(enemyProj))
                  {
                     obj.movingCollisionOccured(new MovingCollision(obj, enemyProj));
                     enemyProj.movingCollisionOccured(new MovingCollision(enemyProj, obj));
                  }
               } 
            }
         } 
      } // end player collisions
      
      // test enemies. We have already caught any player-enemy collisions.
      // aquire the lock so that nothing is adjusted mid-processing
      synchronized(enemyList)
      {
         for(MovingBoundingObject obj : enemyList)
         {
            // things not pushed by geometery can collide with it
            if(!obj.isPushedByGeometry() && isCollidingWithGeometry(obj))
               obj.movingCollisionOccured(new MovingCollision(obj, null));
            // test against player projectiles
            synchronized(playerProjectileList)
            {
               for(MovingBoundingObject playerProj : playerProjectileList)
               {
                  if(obj.isColliding(playerProj))
                  {
                     obj.movingCollisionOccured(new MovingCollision(obj, playerProj));
                     playerProj.movingCollisionOccured(new MovingCollision(playerProj, obj));
                  }
               } 
            } 
         } 
      } // end enemy collisions
      
      // since we've caught all player and enemy collisions, projectiles just need to test for geometry
      synchronized(playerProjectileList)
      {
         for(MovingBoundingObject obj : playerProjectileList)
         {
            if(!obj.isPushedByGeometry() && isCollidingWithGeometry(obj))
               obj.movingCollisionOccured(new MovingCollision(obj, null));
         }
      }
      synchronized(enemyProjectileList)
      {
         for(MovingBoundingObject obj : enemyProjectileList)
         {
            if(!obj.isPushedByGeometry() && isCollidingWithGeometry(obj))
               obj.movingCollisionOccured(new MovingCollision(obj, null));
         }
      }
      
      // since environmental object collide with everything, we'll just do all that here
      // aquire the lock so that nothing is adjusted mid-processing
      synchronized(environmentList)
      {
         for(int i = 0; i < environmentList.size(); i++)
         {
            MovingBoundingObject obj = environmentList.elementAt(i);
            // things not pushed by geometery can collide with it
            if(!obj.isPushedByGeometry() && isCollidingWithGeometry(obj))
               obj.movingCollisionOccured(new MovingCollision(obj, null));
            // test against everything
            // aquire lock for other object
            synchronized(objList)
            {
               for(MovingBoundingObject otherObj : objList)
               {
                  // well, everything except itself
                  if(obj != otherObj)
                  {
                     if(obj.isColliding(otherObj))
                     {
                        obj.movingCollisionOccured(new MovingCollision(obj, otherObj));
                        // don't add reciprocal event for other environmental objs, as they will make their own
                        if(!environmentList.contains(otherObj))
                           otherObj.movingCollisionOccured(new MovingCollision(otherObj, obj));
                     }
                  } 
               }
            }
         } 
      } // end environment collisions
   }
   
   // cut down acceleration to an impulse
   public double convertAccelerationToImpulse(double accl, double secondsElapsed)
   {
      return accl * secondsElapsed;
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
   // probably not great for sizes > 1.0
   public DoublePair getOrthoGeometryCollisionNormals(MovingBoundingObject obj)
   {
      int xLocInt = (int)obj.getXLoc();
      int yLocInt = (int)obj.getYLoc();
      DoublePair bump = new DoublePair(0.0, 0.0);
      if(collisionCheckGeometry(xLocInt, yLocInt - 1, obj, 0.0, -0.01))
         bump.y = 1.0;
      if(collisionCheckGeometry(xLocInt, yLocInt + 1, obj, 0.0, 0.01))
         bump.y = -1.0;
      if(collisionCheckGeometry(xLocInt - 1, yLocInt, obj, -0.01, 0.0))
         bump.x = 1.0;
      if(collisionCheckGeometry(xLocInt + 1, yLocInt, obj, 0.01, 0.0))
         bump.x = -1.0;
      return bump;
   }
   
   // is overlapping with any geometry?
   public boolean isCollidingWithGeometry(MovingBoundingObject obj)
   {
      int[] start = obj.getPotentialCollisionOrigin(0.0);
      int[] end = obj.getPotentialCollisionEnd(0.0);
      for(int xBlock = start[0]; xBlock <= end[0]; xBlock++)
      for(int yBlock = start[1]; yBlock <= end[1]; yBlock++)
      {
         if(collisionCheckGeometry(xBlock, yBlock, obj, 0.0, 0.0))
            return true;
      }
      return false;
   }
   
   public HitscanResult calculateHitscan(DoublePair origin, DoublePair distance){return calculateHitscan(origin, distance, ENVIRONMENT);}
   public HitscanResult calculateHitscan(DoublePair origin, DoublePair distance, int scanType)
   {
      return new HitscanResult(origin, distance, this, scanType);
   }
   
   // returns the first target that will be hit, or null if no such target exists
   public MovingBoundingObject getHitscanImpact(DoublePair origin, DoublePair distance){return getHitscanImpact(origin, distance, ENVIRONMENT);}
   public MovingBoundingObject getHitscanImpact(DoublePair origin, DoublePair distance, int scanType)
   {
      Vector<MovingBoundingObject> targetList = new Vector<MovingBoundingObject>();
      boolean checkPlayerList = true;
      boolean checkEnemyList = true;
      switch(scanType)
      {
         case PLAYER :
         case PLAYER_PROJECTILE :   checkPlayerList = false; break;
         case ENEMY :
         case ENEMY_PROJECTILE :    checkEnemyList = false; break;
         case ENVIRONMENT :         break;
         default :                  throw new Error("Unknown argument for scanType.");
      }
      MovingBoundingObject curTarget = null;
      double curDistance = 1.1;
      SweptAABB collision = null;
      if(checkPlayerList)
      {
         for(MovingBoundingObject prospect : playerList)
         {
            
            collision = new SweptAABB(origin, distance, prospect);
            if(collision.getTime() < 1.0 && collision.getTime() < curDistance)
            {
               curTarget = prospect;
               curDistance = collision.getTime();
            }
         }
      }
      if(checkEnemyList)
         {
         for(MovingBoundingObject prospect : enemyList)
         {
            
            collision = new SweptAABB(origin, distance, prospect);
            if(collision.getTime() < 1.0 && collision.getTime() < curDistance)
            {
               curTarget = prospect;
               curDistance = collision.getTime();
            }
         }
      }
      // always check against environment objects
      for(MovingBoundingObject prospect : environmentList)
      {
         
         collision = new SweptAABB(origin, distance, prospect);
         if(collision.getTime() < 1.0 && collision.getTime() < curDistance)
         {
            curTarget = prospect;
            curDistance = collision.getTime();
         }
      }
      return curTarget;
   }
   
   // returns the (tile imprecise) distance at which the hitscan will impact geometry.
   // this is relative, not absolute
   public DoublePair getHitscanImpactGeometry(DoublePair origin, DoublePair distance)
   {
      double divisor = Math.max(Math.abs(distance.x), Math.abs(distance.y));
      double xStep = distance.x / divisor;
      double yStep = distance.y / divisor;
      
      int steps = 0;
      if(distance.x != 0.0)
         steps = (int)(distance.x / xStep) + 1;
      else if(distance.y != 0.0)
         steps = (int)(distance.y / yStep) + 1;
      int xLoc;
      int yLoc;
      for(int i = 0; i < steps; i++)
      {
         xLoc = (int)(origin.x + (xStep * i));
         yLoc = (int)(origin.y + (yStep * i));
         if(!isInBounds(xLoc, yLoc) || geometry[xLoc][yLoc] == GeometryType.FULL)
            return new DoublePair(xStep * i, yStep * i);
      }
      return distance;
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
      // in bounds, check for disqualifying condintions
      if(isInBounds(x, y))
      {
         switch(geometry[x][y])
         {
            case EMPTY : return false;                         // can't collide, ignore
            case BLOCKS_RIGHT :  if(obj.getXSpeed() <= 0.0)    // ignore if not moving right
                                    return false;
                                 break;
            case BLOCKS_LEFT :   if(obj.getXSpeed() >= 0.0)    // ignore if not moving left
                                    return false;
                                 break;
            case BLOCKS_UP :     if(obj.getYSpeed() >= 0.0)    // ignore if not moving up
                                    return false;
                                 break;
            case BLOCKS_DOWN :   if(obj.getYSpeed() <= 0.0)    // ignore if not moving down
                                    return false;
                                 break;
         }
      }
      
      // OOB or solid tile
      double minX = x - obj.getHalfWidth();
      double minY = y - obj.getHalfHeight();
      double maxX = x + 1.0 + obj.getHalfWidth();
      double maxY = y + 1.0 + obj.getHalfHeight();
      DoublePair shiftedPoint = obj.getLoc();
      shiftedPoint.x += xShift;
      shiftedPoint.y += yShift;
      
      return shiftedPoint.x <= maxX &&
             shiftedPoint.x >= minX &&
             shiftedPoint.y <= maxY &&
             shiftedPoint.y >= minY;
   }

   
   // order list by closest
   private Vector<DoublePair> orderByClosest(Vector<DoublePair> list, DoublePair loc)
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
   public static double getDistanceMetric(DoublePair boxLoc, DoublePair loc)
   {
      double a = boxLoc.x + .5 - loc.x;
      double b = boxLoc.y + .5 - loc.y;
      return (a * a) + (b * b);
   }
   
   
   public GeometryType getGeometryType(int x, int y)
   {
      if(!isInBounds(x, y))
         return GeometryType.FULL;
      return geometry[x][y];
   }
   
   public boolean pointCollidesWithGeometry(DoublePair point){return pointCollidesWithGeometry(point, (int)point.x, (int)point.y);}
   public boolean pointCollidesWithGeometry(DoublePair point, int x, int y)
   {
      double xLoc = point.x - x;
      double yLoc = point.y - y;
      // false if not in box at all
      if(xLoc < 0.0 || xLoc > 1.0 ||
         yLoc < 0.0 || yLoc > 1.0)
         return false;
      switch(getGeometryType(x, y))
      {
         case FULL : return true;
         case EMPTY : return false;
      }
      return true;
   }
}