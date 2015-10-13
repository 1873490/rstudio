/*
 * ShortcutManager.java
 *
 * Copyright (C) 2009-15 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.core.client.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rstudio.core.client.BrowseCap;
import org.rstudio.core.client.StringUtil;
import org.rstudio.core.client.command.KeyboardShortcut.KeyCombination;
import org.rstudio.core.client.command.KeyboardShortcut.KeySequence;
import org.rstudio.core.client.events.NativeKeyDownEvent;
import org.rstudio.core.client.events.NativeKeyDownHandler;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.application.events.EventBus;
import org.rstudio.studio.client.workbench.commands.Commands;
import org.rstudio.studio.client.workbench.commands.RStudioCommandExecutedFromShortcutEvent;
import org.rstudio.studio.client.workbench.views.source.editors.text.ace.AceKeyboardActivityEvent;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.user.client.Command;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.Event.NativePreviewEvent;
import com.google.gwt.user.client.Event.NativePreviewHandler;
import com.google.gwt.user.client.Timer;
import com.google.inject.Inject;

public class ShortcutManager implements NativePreviewHandler,
                                        NativeKeyDownHandler
{
   public interface Handle
   {
      void close();
   }

   public static final ShortcutManager INSTANCE = new ShortcutManager();

   private ShortcutManager()
   {
      keyBuffer_ = new KeySequence();
      keyTimer_ = new Timer()
      {
         @Override
         public void run()
         {
            keyBuffer_.clear();
         }
      };
      
      // Defer injection because the ShortcutManager is constructed
      // very eagerly (to allow for codegen stuff in ShortcutsEmitter
      // to work)
      Scheduler.get().scheduleDeferred(new ScheduledCommand()
      {
         @Override
         public void execute()
         {
            RStudioGinjector.INSTANCE.injectMembers(ShortcutManager.this);
            events_.addHandler(
                  AceKeyboardActivityEvent.TYPE,
                  new AceKeyboardActivityEvent.Handler()
                  {
                     @Override
                     public void onAceKeyboardActivity(AceKeyboardActivityEvent event)
                     {
                        if (!event.isChainEvent())
                           keyBuffer_.clear();
                     }
                  });
         }
      });
      
      // NOTE: Because this class is used as a singleton and is never
      // destroyed it's not necessary to manage lifetime of this event handler
      Event.addNativePreviewHandler(this);
      addPostViewHandler();
      
      if (BrowseCap.isLinuxDesktop())
         addMiddleClickPasteListener();
   }
   
   private void addMiddleClickPasteListener()
   {
      Event.addNativePreviewHandler(new NativePreviewHandler()
      {
         @Override
         public void onPreviewNativeEvent(NativePreviewEvent preview)
         {
            if (preview.getTypeInt() == Event.ONCLICK)
            {
               NativeEvent event = preview.getNativeEvent();
               if (isMiddleClick(event))
               {
                  commands_.pasteDummy().execute();
               }
            }
         }
      });
   }
   
   private static final native boolean isMiddleClick(NativeEvent event) /*-{
      return event.which === 2;
   }-*/;
   
   private native final void addPostViewHandler() /*-{
      var self = this;
      $doc.body.addEventListener("keydown", $entry(function(evt) {
         self.@org.rstudio.core.client.command.ShortcutManager::swallowEvents(Ljava/lang/Object;)(evt);
      }));
   }-*/;
   
   @Inject
   private void initialize(ApplicationCommandManager appCommands,
                           EditorCommandManager editorCommands,
                           UserCommandManager userCommands,
                           EventBus events,
                           Commands commands)
   {
      appCommands_ = appCommands;
      editorCommands_ = editorCommands;
      userCommands_ = userCommands;
      events_ = events;
      commands_ = commands;
   }

   public boolean isEnabled()
   {
      return disableCount_ == 0;
   }

   public Handle disable()
   {
      disableCount_++;
      return new Handle()
      {
         private boolean closed_ = false;

         @Override
         public void close()
         {
            if (!closed_)
               disableCount_--;
            closed_ = true;
         }
      };
   }
   
   public void addCustomBinding(KeySequence keys, AppCommand command)
   {
      updateKeyPrefixes(keys);
      customBindings_.addCommandBinding(keys, command);
      maskedCommands_.put(command, true);
   }
   
   public void clearCustomBindings()
   {
      customBindings_.clear();
      maskedCommands_.clear();
      refreshKeyPrefixes();
   }
   
   public void register(int modifiers, 
                        int keyCode, 
                        AppCommand command, 
                        String groupName, 
                        String title,
                        String disableModes)
   {
      if (!BrowseCap.hasMetaKey() && (modifiers & KeyboardShortcut.META) != 0)
         return;
      
      register(
            new KeySequence(keyCode, modifiers),
            command,
            groupName,
            title,
            disableModes);
   }
   
   public void register(int m1,
                        int k1,
                        int m2,
                        int k2,
                        AppCommand command,
                        String groupName,
                        String title,
                        String disableModes)
   {
      KeySequence sequence = new KeySequence();
      sequence.add(k1, m1);
      sequence.add(k2, m2);
      register(sequence, command, groupName, title, disableModes);
   }
   
   public void register(KeySequence keys, AppCommand command)
   {
      register(keys, command, "", "", "");
   }
   
   private void refreshKeyPrefixes()
   {
      prefixes_.clear();
      for (KeySequence keys : appCommandBindings_.keySet())
         updateKeyPrefixes(keys);
      
      for (KeySequence keys : customBindings_.keySet())
         updateKeyPrefixes(keys);
   }
   
   private void updateKeyPrefixes(KeyboardShortcut shortcut)
   {
      updateKeyPrefixes(shortcut.getKeySequence());
   }
   
   private void updateKeyPrefixes(KeySequence keys)
   {
      if (keys.size() <= 1)
         return;
      
      KeySequence prefixes = new KeySequence();
      for (int i = 0; i < keys.size() - 1; i++)
      {
         prefixes.add(keys.get(i));
         prefixes_.add(prefixes.clone());
      }
   }
   
   public void register(KeySequence keys,
                        AppCommand command,
                        String groupName,
                        String title,
                        String disableModes)
   {
      KeyboardShortcut shortcut = 
            new KeyboardShortcut(keys, groupName, title, disableModes);
      
      // Update state related to key dispatch.
      updateKeyPrefixes(shortcut);
      
      if (command == null)
      {
         // If the shortcut is unbound, check to see whether there's another
         // unbound shortcut with the same title; replace it if there is.
         boolean existingShortcut = false;
         for (int i = 0; i < unboundShortcuts_.size(); i++) {
            if (unboundShortcuts_.get(i).getTitle().equals(title)) {
               unboundShortcuts_.set(i, shortcut);
               existingShortcut = true;
               break;
            }
         }
         if (!existingShortcut)
            unboundShortcuts_.add(shortcut);
      }
      else
      {
         // Setting the shortcut on a command is done purely for UI-related tasks.
         // We don't want to set modal shortcuts (the binding will be active regardless
         // of whether we let the command 'know' about the binding).
         if (disableModes.indexOf("default") != 0)
            command.setShortcut(shortcut);
         
         appCommandBindings_.addCommandBinding(keys, command, shortcut);
      }
   }
   
   public static int parseDisableModes(String disableModes)
   {
      int mode = KeyboardShortcut.MODE_NONE;
      
      if (StringUtil.isNullOrEmpty(disableModes))
         return mode;
      
      String[] splat = disableModes.split(",");
      for (String item : splat)
      {
         if (item.equals("default"))
            mode |= KeyboardShortcut.MODE_DEFAULT;
         else if (item.equals("vim"))
            mode |= KeyboardShortcut.MODE_VIM;
         else if (item.equals("emacs"))
            mode |= KeyboardShortcut.MODE_EMACS;
         else
            assert false: "Unrecognized 'disableModes' value '" + item + "'";
      }
      
      return mode;
   }
   
   public void onKeyDown(NativeKeyDownEvent evt)
   {
      if (evt.isCanceled())
         return;

      keyTimer_.schedule(3000);
      if (handleKeyDown(evt.getEvent()))
      {
         evt.cancel();
         events_.fireEvent(new RStudioCommandExecutedFromShortcutEvent());
      }
   }

   public void onPreviewNativeEvent(NativePreviewEvent event)
   {
      if (event.isCanceled())
         return;

      keyTimer_.schedule(3000);
      if (event.getTypeInt() == Event.ONKEYDOWN)
      {
         if (handleKeyDown(event.getNativeEvent()))
         {
            event.cancel();
            events_.fireEvent(new RStudioCommandExecutedFromShortcutEvent());
         }
      }
   }
   
   public void setEditorMode(int editorMode)
   {
      editorMode_ = editorMode;
   }
   
   public List<ShortcutInfo> getActiveShortcutInfo()
   {
      List<ShortcutInfo> info = new ArrayList<ShortcutInfo>();
      
      HashMap<Command, ShortcutInfo> infoMap = 
            new HashMap<Command, ShortcutInfo>();
      ArrayList<KeyboardShortcut> shortcuts = 
            new ArrayList<KeyboardShortcut>();
      
      // Create a ShortcutInfo for each unbound shortcut
      for (KeyboardShortcut shortcut: unboundShortcuts_)
         info.add(new ShortcutInfo(shortcut, null));

      // Sort the shortcuts as they were presented in Commands.cmd.xml
      for (Map.Entry<KeySequence, List<AppCommandBinding>> entry : appCommandBindings_.entrySet())
         for (AppCommandBinding binding : entry.getValue())
            shortcuts.add(binding.getShortcut());
      
      Collections.sort(shortcuts, new Comparator<KeyboardShortcut>()
      {
         @Override
         public int compare(KeyboardShortcut o1, KeyboardShortcut o2)
         {
            return o1.getOrder() - o2.getOrder();
         }
      });

      // Create a ShortcutInfo for each command (a command may have multiple
      // shortcut bindings)
      for (KeyboardShortcut shortcut: shortcuts)
      {
         AppCommand command = appCommandBindings_.getCommand(shortcut.getKeySequence(), editorMode_);
         if (infoMap.containsKey(command))
         {
            infoMap.get(command).addShortcut(shortcut);
         }
         else
         {
            ShortcutInfo shortcutInfo = new ShortcutInfo(shortcut, command);
            info.add(shortcutInfo);
            infoMap.put(command, shortcutInfo);
         }
      }
      // Sort the commands back into the order in which they were created 
      // (reading them out of the keyset mangles the original order)
      Collections.sort(info, new Comparator<ShortcutInfo>()
      {
         @Override
         public int compare(ShortcutInfo o1, ShortcutInfo o2)
         {
            return o1.getOrder() - o2.getOrder();
         }
      });
      return info;
   }

   private boolean handleKeyDown(NativeEvent e)
   {
      // Don't dispatch on bare modifier keypresses.
      if (KeyboardHelper.isModifierKey(e.getKeyCode()))
         return false;
      
      keyBuffer_.add(e);
      KeyboardShortcut shortcut = new KeyboardShortcut(keyBuffer_.clone());
      
      // If this matches a prefix key, return false early.
      if (prefixes_.contains(shortcut.getKeySequence()))
         return false;
      
      // Clear the key buffer (we've reached a 'leaf' for the
      // key sequence chain; there may or may not be a command)
      keyBuffer_.clear();
      
      // Check for user-defined commands.
      if (userCommands_.dispatch(shortcut))
         return true;
      
      // Check for custom bindings for RStudio AppCommands.
      if (dispatch(shortcut, customBindings_, e))
         return true;
      
      // Check for RStudio AppCommands.
      if (dispatch(shortcut, appCommandBindings_, e, maskedCommands_))
         return true;
      
      return false;
   }
   
   private boolean dispatch(KeyboardShortcut shortcut,
                            AppCommandBindings bindings,
                            NativeEvent event)
   {
      return dispatch(shortcut, bindings, event, null);
   }
   
   private boolean dispatch(KeyboardShortcut shortcut,
                            AppCommandBindings bindings,
                            NativeEvent event,
                            Map<AppCommand, Boolean> maskedCommandsMap)
   {
      KeySequence keys = shortcut.getKeySequence();
      
      // If the shortcut manager is disabled, bail
      if (!isEnabled())
         return false;
      
      // If we have no binding, bail
      if (!bindings.containsKey(keys))
         return false;
      
      AppCommand command = bindings.getCommand(keys, editorMode_, maskedCommandsMap);
      if (command == null)
         return false;
      
      event.preventDefault();
      command.executeFromShortcut();
      return true;
   }
   
   private void swallowEvents(Object object)
   {
      NativeEvent event = (NativeEvent) object;
      
      // Suppress save / quit events from reaching the browser
      KeyCombination keys = new KeyCombination(event);
      int keyCode = keys.getKeyCode();
      int modifiers = keys.getModifier();
      
      boolean isSaveQuitKey =
            keyCode == KeyCodes.KEY_S ||
            keyCode == KeyCodes.KEY_W;
      
      boolean isSaveQuitModifier = BrowseCap.isMacintosh() ?
            modifiers == KeyboardShortcut.META :
            modifiers == KeyboardShortcut.CTRL;
      
      if (isSaveQuitKey && isSaveQuitModifier)
         event.preventDefault();
   }
   
   private int disableCount_ = 0;
   private int editorMode_ = KeyboardShortcut.MODE_DEFAULT;
   
   private final KeySequence keyBuffer_;
   private final Timer keyTimer_;
   
   private static class AppCommandBinding
   {
      public AppCommandBinding(AppCommand command, KeyboardShortcut shortcut)
      {
         command_ = command;
         shortcut_ = shortcut;
      }
      
      public AppCommand getCommand()
      {
         return command_;
      }
      
      public KeyboardShortcut getShortcut()
      {
         return shortcut_;
      }

      private final AppCommand command_;
      private final KeyboardShortcut shortcut_;
   }
   
   private static class AppCommandBindings
   {
      public AppCommandBindings()
      {
         bindings_ = new HashMap<KeySequence, List<AppCommandBinding>>();
      }
      
      public void addCommandBinding(KeySequence keys, AppCommand command)
      {
         addCommandBinding(keys, command, new KeyboardShortcut(keys));
      }
      
      public void addCommandBinding(KeySequence keys, AppCommand command, KeyboardShortcut shortcut)
      {
         if (!bindings_.containsKey(keys))
            bindings_.put(keys, new ArrayList<AppCommandBinding>());
         
         List<AppCommandBinding> commands = bindings_.get(keys);
         commands.add(new AppCommandBinding(command, shortcut));
      }
      
      public AppCommand getCommand(KeySequence keys,
                                   int editorMode)
      {
         return getCommand(keys, editorMode, null);
      }
      
      public AppCommand getCommand(KeySequence keys,
                                   int editorMode,
                                   Map<AppCommand, Boolean> maskedCommands)
      {
         if (!bindings_.containsKey(keys))
            return null;
         
         List<AppCommandBinding> commands = bindings_.get(keys);
         for (AppCommandBinding binding : commands)
         {
            int disableModes = binding.getShortcut().getDisableModes();
            AppCommand command = binding.getCommand();
            
            // If this command is masked by another command, skip it.
            if (maskedCommands != null && maskedCommands.containsKey(command))
               continue;
            
            // Check to see whether this command is enabled for the current
            // editor mode.
            boolean enabled =
                  command.isEnabled() &&
                  (disableModes & editorMode) == 0;
            
            if (enabled)
               return command;
         }
         
         return null;
      }
      
      public Set<KeySequence> keySet() { return bindings_.keySet(); }
      public Set<Map.Entry<KeySequence, List<AppCommandBinding>>> entrySet() { return bindings_.entrySet(); }
      public boolean containsKey(KeySequence keys) { return bindings_.containsKey(keys); }
      public void clear() { bindings_.clear(); }
      
      private final Map<KeySequence, List<AppCommandBinding>> bindings_;
   }
   
   private final AppCommandBindings appCommandBindings_ =
         new AppCommandBindings();
   
   private final AppCommandBindings customBindings_ =
         new AppCommandBindings();
   
   private final Map<AppCommand, Boolean> maskedCommands_ =
         new HashMap<AppCommand, Boolean>();
   
   private List<KeyboardShortcut> unboundShortcuts_ =
         new ArrayList<KeyboardShortcut>();
   
   private Set<KeySequence> prefixes_ =
         new HashSet<KeySequence>();
   
   // Injected ----
   private UserCommandManager userCommands_;
   private EditorCommandManager editorCommands_;
   private ApplicationCommandManager appCommands_;
   private EventBus events_;
   private Commands commands_;
   
}
