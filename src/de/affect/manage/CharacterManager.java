/*
 * CharacterManager.java
 *
 * Copyright (c) 2004-2008 by Patrick Gebhard
 * All rights reserved.
 *
 */
package de.affect.manage;

import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

import de.affect.compute.EmotionEngine;
import de.affect.compute.DecayFunction;
import de.affect.compute.MoodEngine;
import de.affect.appraisal.EEC;
import de.affect.emotion.EmotionHistory;
import de.affect.emotion.EmotionVector;
import de.affect.emotion.Emotion;
import de.affect.mood.Mood;
import de.affect.personality.Personality;
import de.affect.personality.PersonalityEmotionsRelations;
import de.affect.gui.AffectMonitor;
import de.affect.gui.AffectStatusDisplay;
import de.affect.gui.AffectMonitorFrame;

import de.affect.data.AffectConsts;
import de.affect.emotion.EmotionType;
import de.affect.emotion.PADEmotion;
import static de.affect.gui.AlmaGUI.sIntegratedDesktopMode;
import de.affect.manage.event.EmotionMaintenanceEvent;
import de.affect.manage.event.EmotionMaintenanceListener;

import static de.affect.personality.PersonalityMoodRelations.getDefaultMood;
import static de.affect.personality.PersonalityMoodRelations.getDefaultMood;
import static java.lang.Thread.sleep;

/**
 * The class
 * <code>CharacterManager</code> stores all necessary data and provides emotion
 * and mood computation functions.
 *
 * @author Patrick Gebhard
 *
 * @version 1.0
 */
public class CharacterManager extends EntityManager implements EmotionMaintenanceListener {

  public static Logger sLog = Logger.getLogger("Alma");
  private static AffectManager.InterfaceHolder affectManager = AffectManager.sInterface;
  private CharacterManager fCharacterManagerInstance = null;
  private PersonalityEmotionsRelations fPersEmoRels = null;
  private MoodEngine fMoodEngine = null;
  private EmotionEngine fEmotionEngine = null;
  private EmotionHistory fEmotionHistory = null;
  private AffectMonitor fAffectMonitor = null;
  private AffectStatusDisplay fAffectStatusDisplay = null;
  private boolean fDerivedPersonality = false;
  private DecayFunction fDecayFunction = null;
  private Timer fDecayTimer = null;
  private Timer fMoodComputationTimer = null;
  private Timer fInternalAppraisalTimer = null;
  private boolean fShowAffectMonitor = false;
  boolean fAffectComputationPaused = false;

  public CharacterManager(String name, Personality personality,
    AffectConsts ac, boolean derivedPersonality,
    DecayFunction decayFunction,
    List<EmotionType> emotions) {
    super(name);
    fCharacterManagerInstance = this;
    fPersonality = personality;
    fAc = ac;
    fDerivedPersonality = derivedPersonality;
    fDecayFunction = decayFunction;
    fAvailEmotions = emotions;
    fPersEmoRels = personality.getPersonalityEmotionsRelations();
    fDefaultMood = getDefaultMood(personality);
    fCurrentMood = getDefaultMood(personality);
    // Setup emotion processing
    fEmotionVector = createEmotionVector();
    fEmotionHistory = new EmotionHistory();
    fDecayFunction.init(fAc.emotionDecaySteps);
    fEmotionEngine = new EmotionEngine(fPersonality, fDecayFunction);
    fDecayTimer = new Timer(true);
    fDecayTimer.schedule(new EmotionDecayTask(), fAc.emotionDecayPeriod, fAc.emotionDecayPeriod);
    // Setup mood processing	
    fMoodEngine = new MoodEngine(fPersonality, fAc.moodStabilityControlledByNeurotism,
      fDefaultMood, fAc.moodReturnOverallTime);
    fMoodComputationTimer = new Timer(true);
    fMoodComputationTimer.schedule(new MoodComputationTask(), fAc.moodReturnPeriod, fAc.moodReturnPeriod);
    // Setup affect monitoring
    if (!sIntegratedDesktopMode) {
      Thread startAffectMonitor = new Thread() {
        @Override
        public void run() {
          fAffectMonitor = (AffectMonitor) new AffectMonitorFrame(fName, fEmotionVector, fCurrentMood);
          fAffectMonitor.addEmotionMaintenanceListener(fCharacterManagerInstance);
        }
      };
      startAffectMonitor.start();
    }
  }

  /**
   * The
   * <code>EmotionDecayTask</code> class manages the emotion decay process.
   */
  private class EmotionDecayTask extends TimerTask {

    private EmotionDecayTask() {
    }

    public synchronized void run() {
      fEmotionVector =
        fEmotionEngine.decay(fEmotionHistory, fEmotionVector, createEmotionVector());
      if ((fAffectMonitor != null) && fShowAffectMonitor) {
        fAffectMonitor.updateEmotionDisplay(fName, fEmotionVector);
      }
      if ((fAffectStatusDisplay != null)) {
        fAffectStatusDisplay.updateStatusDisplay(fName, fEmotionVector,
          fDefaultMood, fCurrentMood, fCurrentMoodTendency);
      }
    }
  }

  /**
   * The
   * <code>EmotionMonitorTask</code> class displays the elicited emotions. This
   * is usually done by the EmotionDecayTask, but in case it is diabled some
   * other method has to monitor the emotions.
   */
  private class EmotionMonitorTask extends TimerTask {

    private EmotionMonitorTask() {
    }

    public synchronized void run() {
      if ((fAffectMonitor != null) && fShowAffectMonitor) {
        fAffectMonitor.updateEmotionDisplay(fName, fEmotionVector);
      }
      if ((fAffectStatusDisplay != null)) {
        fAffectStatusDisplay.updateStatusDisplay(fName, fEmotionVector,
          fDefaultMood, fCurrentMood, fCurrentMoodTendency);
      }
    }
  }

  /**
   * Starts emotion decay, if not running.
   */
  public void enableEmotionDecay() {
    if (!fAffectComputationPaused) {
      fDecayTimer.cancel();
      fDecayTimer = new Timer(true);
      fDecayTimer.schedule(new EmotionDecayTask(), fAc.emotionDecayPeriod, fAc.emotionDecayPeriod);
    }
  }

  /**
   * Starts emotion monitoring, if not running.
   */
  public void enableEmotionMonitoring() {
    fDecayTimer = new Timer(true);
    fDecayTimer.schedule(new EmotionMonitorTask(), fAc.emotionDecayPeriod, fAc.emotionDecayPeriod);
  }

  /**
   * Stops emotion decay, if running.
   */
  public void disableEmotionDecay() {
    fDecayTimer.cancel();
  }

  /**
   * Returns a flag if affect computation is paused.
   */
  public boolean isAffectComputationPaused() {
    return fAffectComputationPaused;
  }

  /**
   * Pauses all dynamic affect computation
   *
   * @return true if the affect computation is pause, false otherwise
   */
  public boolean pauseAffectComputation() {
    sLog.info(fName + " affect computation paused ...");
    stopAll();
    fAffectComputationPaused = true;
    return true;
  }

  /**
   * Resumes a paused dynamic affect computation
   *
   * @return true if the affect computation is continued, false otherwise
   */
  public boolean resumeAffectComputation() {
    if (fAffectComputationPaused) {
      sLog.info(fName + " emotion computation resumed ...");

      fDecayTimer = new Timer(true);
      fDecayTimer.schedule(new EmotionDecayTask(), fAc.emotionDecayPeriod, fAc.emotionDecayPeriod);

      sLog.info(fName + " mood computation resumed ...");
      fMoodEngine = new MoodEngine(fPersonality, fAc.moodStabilityControlledByNeurotism, fDefaultMood, fAc.moodReturnOverallTime);
      fMoodComputationTimer.cancel();
      fMoodComputationTimer = new Timer(true);
      fMoodComputationTimer.schedule(new MoodComputationTask(), fAc.moodReturnPeriod, fAc.moodReturnPeriod);

      fAffectComputationPaused = false;
      return true;
    } else {
      return false;
    }
  }

  /**
   * Performs a single step for a paused affect computation (emotion decay and
   * mood computation)
   *
   * @return true if one step affect computation is performed correctly, false
   * otherwise
   */
  public boolean stepwiseAffectComputation() {
    if (fAffectComputationPaused) {

      EmotionDecayTask ed = new EmotionDecayTask();
      ed.run();
      MoodComputationTask mc = new MoodComputationTask();
      mc.run();

      sLog.info(AffectManager.sInterface.getCurrentAffect().toString());

      return true;
    } else {
      return false;
    }
  }

  /**
   * Sets the visible state of the character's affect monitor
   *
   * @param visible true shows the character's affect monitor, false otherwise
   */
  public void showMonitor(final boolean visible) {

    // TODO: Put the operation into a thread if the affectMonitor object

    // has not been yet instanciated
    if ((fAffectMonitor == null) && visible) {
      Thread waitNShowMonitor = new Thread() {
        @Override
        public void run() {
          boolean exit = false;
          while (!exit) {
            //debug log.info("Waiting for affect monitor ...");
            if (fAffectMonitor != null) {
              exit = true;
              fShowAffectMonitor = visible;
              fAffectMonitor.showFrame(visible);
            }
            try {
              sleep(500);
            } catch (InterruptedException ie) {
              ie.printStackTrace();
            }
          }
        }
      };
      waitNShowMonitor.start();
      return;
    }
    if (fAffectMonitor != null) {
      fShowAffectMonitor = visible;
      fAffectMonitor.showFrame(visible);
    }
  }

  /**
   * Returns the visible state of the character's affect monitor
   *
   * @return true if the character's affect monitor is visible, false otherwise
   */
  public boolean hasActiveAffectMonitor() {
    return fShowAffectMonitor;
  }

  /**
   * Returns the character's affect monitor
   *
   * @return true if the character's affect monitor is visible, false otherwise
   */
  public AffectMonitor getAffectMonitor() {
    return fAffectMonitor;
  }

  /**
   * Sets a new affect monitor for this character.
   *
   * @param affectMonitor the new affect monitor
   */
  public synchronized void setAffectMonitor(AffectMonitor affectMonitor) {
    disableEmotionDecay();
    disableMoodComputation();
    if (affectMonitor != null) {
      fAffectMonitor = affectMonitor;
      fShowAffectMonitor = true;
      fAffectMonitor.addEmotionMaintenanceListener(fCharacterManagerInstance);
      fAffectMonitor.updateEmotionDisplay(fName, fEmotionVector);
      fAffectMonitor.updateMoodDisplay(fName, fEmotionVector, fMoodEngine.getEmotionsCenter(),
        fDefaultMood, fCurrentMood, fCurrentMoodTendency);
    }
    enableMoodComputation();
    enableEmotionDecay();
  }

  /**
   * Sets a new affect status display for this character.
   *
   * @param affectStatus the new affect status display
   */
  public synchronized void setAffectStatusDisplay(AffectStatusDisplay affectStatus) {
    disableEmotionDecay();
    disableMoodComputation();
    if (affectStatus != null) {
      fAffectStatusDisplay = affectStatus;
      fAffectStatusDisplay.updateStatusDisplay(fName, fEmotionVector,
        fDefaultMood, fCurrentMood, fCurrentMoodTendency);
    }
    enableMoodComputation();
    enableEmotionDecay();
  }

  /**
   * The
   * <code>MoodComputationTask</code> class manages the compuatation of the
   * actual mood.
   */
  private class MoodComputationTask extends TimerTask {

    private MoodComputationTask() {
    }

    public synchronized void run() {
      fCurrentMood = fMoodEngine.compute(fCurrentMood, fEmotionVector);
      fCurrentMoodTendency = fMoodEngine.getCurrentMoodTendency();
      if ((fAffectMonitor != null) && fShowAffectMonitor) {
        fAffectMonitor.updateMoodDisplay(fName, fEmotionVector, fMoodEngine.getEmotionsCenter(),
          fDefaultMood, fCurrentMood, fCurrentMoodTendency);
      }
    }
  }

  /**
   * Starts mood computation, if not running.
   */
  public void enableMoodComputation() {
    if (!fAffectComputationPaused) {
      fMoodEngine = new MoodEngine(fPersonality, fAc.moodStabilityControlledByNeurotism, fDefaultMood, fAc.moodReturnOverallTime);
      fMoodComputationTimer.cancel();
      fMoodComputationTimer = new Timer(true);
      fMoodComputationTimer.schedule(new MoodComputationTask(), fAc.moodReturnPeriod, fAc.moodReturnPeriod);
    }
  }

  /**
   * Stops mood computation, if running.
   */
  public void disableMoodComputation() {
    fMoodComputationTimer.cancel();
  }

  public void addEEC(EEC eec) {
    fEmotionEngine.addEEC(eec);
  }

  /**
   * Sets a new emotion decay function and decaySteps and reinitialize the
   * character's emotion computation engine.
   *
   * @param decayFunction the new emotion decay function
   */
  public synchronized void setDecayFunction(DecayFunction decayFunction) {
    disableEmotionDecay();
    fDecayFunction = decayFunction;
    fDecayFunction.init(fAc.emotionDecaySteps);
    fEmotionEngine = new EmotionEngine(fPersonality, fDecayFunction);
    enableEmotionDecay();
  }

  /**
   * Returns the character's actual decay function
   *
   * @return the character's actual decay function
   */
  public synchronized DecayFunction getDecayFunction() {
    return fDecayFunction;
  }

  /**
   * Returns the character's emotion history containing all active emotions
   *
   * @return an EmotionHistory object
   */
  public synchronized EmotionHistory getEmotionHistory() {
    return fEmotionHistory;
  }

  /**
   * Returns the character's emotion engine
   *
   * @return an EmotionEngine object
   */
  public synchronized EmotionEngine getEmotionEngine() {
    return fEmotionEngine;
  }

  /**
   * Sets a new personality for this character and reinitialize the character's
   * emotion computation engine.
   *
   * @param personality the new personality
   */
  public synchronized void setPersonality(Personality personality) {
    disableEmotionDecay();
    disableMoodComputation();

    fPersonality = personality;
    fEmotionVector = createEmotionVector();
    fEmotionHistory = new EmotionHistory();
    fEmotionEngine = new EmotionEngine(fPersonality, fDecayFunction);
    fDefaultMood = getDefaultMood(personality);
    fCurrentMood = getDefaultMood(personality);

    if ((fAffectMonitor != null) && fShowAffectMonitor) {
      fAffectMonitor.updateEmotionDisplay(fName, fEmotionVector);
      fAffectMonitor.updateMoodDisplay(fName, fEmotionVector, fMoodEngine.getEmotionsCenter(),
        fDefaultMood, fCurrentMood, fCurrentMoodTendency);
    }

    enableMoodComputation();
    enableEmotionDecay();
  }

  public synchronized void setAffectConsts(AffectConsts ac) {
    disableEmotionDecay();
    disableMoodComputation();
    fAc = ac;
    PersonalityEmotionsRelations perEmoRels =
      fPersonality.getPersonalityEmotionsRelations();
    perEmoRels.setPersonalityEmotionInfluence(ac.personalityEmotionInfluence);
    perEmoRels.setEmotionMaxBaseline(ac.emotionMaxBaseline);
    fPersonality.setPersonalityEmotionsRelations(perEmoRels);
    fEmotionEngine = new EmotionEngine(fPersonality, fDecayFunction);
    enableMoodComputation();
    enableEmotionDecay();
  }

  @Override
  public synchronized AffectConsts getAffectConsts() {
    return fAc;
  }

  /**
   * Infers emotions based on the specified list of Emotion Eliciting Conditions
   * and updates the character's emotional state displayed by the affect
   * monitor.
   *
   * @return generated emotions
   */
  public synchronized EmotionVector inferEmotions() {
    EmotionVector result = createEmotionVector();
    result = fEmotionEngine.inferEmotions(result, fEmotionHistory, fCurrentMood);
    fEmotionEngine.clearEEC();
    fEmotionHistory.add(result);
    fEmotionVector = fEmotionHistory.getEmotionalState(fEmotionVector);

    if ((fAffectMonitor != null) && fShowAffectMonitor) {
      fAffectMonitor.updateEmotionDisplay(fName, fEmotionVector);
      fAffectMonitor.updateMoodDisplay(fName, fEmotionVector, fMoodEngine.getEmotionsCenter(),
        fDefaultMood, fCurrentMood, fCurrentMoodTendency);
    }
    return result;
  }

  public synchronized EmotionVector infuseBioSignalEmotions(PADEmotion e) {
    if (fAffectComputationPaused) {
      // if the affect computation is paused, return the last active affect output document
      return fEmotionHistory.getEmotionalState(fEmotionVector);
    }

    EmotionVector result = createEmotionVector();
    result.add(e);
    fEmotionHistory.add(result);
    fEmotionVector = fEmotionHistory.getEmotionalState(fEmotionVector);

    if ((fAffectMonitor != null) && fShowAffectMonitor) {
      fAffectMonitor.updateEmotionDisplay(fName, fEmotionVector);
      fAffectMonitor.updateMoodDisplay(fName, fEmotionVector, fMoodEngine.getEmotionsCenter(),
        fDefaultMood, fCurrentMood, fCurrentMoodTendency);
    }
    return result;
  }

  /**
   * Prints all emotions in the specified collection whose intensity is greater
   * than the baseline.
   */
  private void printEmotions() {
    String newLine = System.getProperty("line.separator");
    String indent = " ";
    StringBuffer sb = new StringBuffer("[EmotionVector: ");
    for (Iterator it = fEmotionVector.getEmotions().iterator(); it.hasNext();) {
      Emotion emotion = (Emotion) it.next();
      if (emotion.getIntensity() > emotion.getBaseline()) {
        sb.append(indent);
        sb.append(emotion.toString());
        sb.append(newLine);
      }
    }
    sb.append("]");
    sLog.info(sb.toString());
  }

  public boolean isDerivedPersonality() {
    return fDerivedPersonality;
  }

  /**
   * Stops all affect processing, decaying tasks
   */
  public void stopAll() {
    fDecayTimer.cancel();
    fMoodComputationTimer.cancel();
  }

  /**
   * Implements EmotionMaintenanceListener
   */
  public void maintainEmotion(EmotionMaintenanceEvent e) {
    EmotionType changedEmotionType = e.emotionType();

    sLog.info(fName + " got maintain motion event " + e.toString());
    sLog.info("\t" + e.getCharacterName());

    // only add the emotion to the right character :-)
    if (e.getCharacterName().equals(fName)) {
      EmotionVector emotions = createEmotionVector();
      List freshDummyEmotions = emotions.getEmotions();
      for (Iterator it = freshDummyEmotions.iterator(); it.hasNext();) {
        Emotion emotion = (Emotion) it.next();
        EmotionType emotionType = emotion.getType();
        if (changedEmotionType.equals(emotionType)) {
          if (emotionType.equals(EmotionType.Physical)) {
            // This is a simulation of pad values derived from physical biosensor data
            double p = (Math.random() - 0.5) * 2;
            double a = (Math.random() - 0.5) * 2;
            double d = (Math.random() - 0.5) * 2;
            Emotion newEmotion = new PADEmotion(new Mood(p, a, d), e.intensity(), "Simulated Bio Sensor Input");
            emotions.add(newEmotion);
          } else {
            double intensity = (e.intensity() < emotion.getBaseline()) ? emotion.getBaseline() : e.intensity();
            Emotion newEmotion = new Emotion(emotionType, intensity, emotion.getBaseline(), "User maintenance");
            emotions.add(newEmotion);
          }
        }
      }
      fEmotionHistory.add(emotions);
      fEmotionVector = fEmotionHistory.getEmotionalState(fEmotionVector);

      // show elicited emotion(s) in affect monitor even if affect computation is paused
      if ((fAffectMonitor != null) && fShowAffectMonitor) {
        fAffectMonitor.updateEmotionDisplay(fName, fEmotionVector);

        fAffectMonitor.updateMoodDisplay(fName, fEmotionVector, fMoodEngine.getEmotionsCenter(),
          fDefaultMood, fCurrentMood, fCurrentMoodTendency);
      }
    }
  }
}