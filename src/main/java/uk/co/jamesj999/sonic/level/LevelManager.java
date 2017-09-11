package uk.co.jamesj999.sonic.level;

public class LevelManager {
   private static LevelManager levelManager;
   private Level level;

   public void setLevel(Level level) {
      this.level = level;
   }

   public Level getLevel() {
      return level;
   }

   public synchronized static LevelManager getInstance() {
      if (levelManager == null) {
         levelManager = new LevelManager();
      }
      return levelManager;
   }
}
