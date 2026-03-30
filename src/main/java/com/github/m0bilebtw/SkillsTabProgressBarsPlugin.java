package com.github.m0bilebtw;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.awt.Color;
import java.util.EnumSet;
import java.util.Set;

@PluginDescriptor(
        name = "Skills Progress Bars",
        description = "Adds progress bars to the skills tab to show how close the next level ups are",
        tags = {"skills", "stats", "levels", "goals", "progress", "bars", "darken"}
)
@Slf4j
public class SkillsTabProgressBarsPlugin extends Plugin {
    private static enum BarType
    {
        PRIMARY,
        SECONDARY,
        SKILL_DIVIDER
    };

    private static final int SCRIPTID_STATS_INIT = 394;
    private static final int SCRIPTID_STATS_REFRESH = 393;
    private static final int SCRIPTID_STATS_SKILLTOTAL = 396;

    private static final int CONTAINER_SIZE = 20;
    private static final int SEGMENT_SIZE = 2;   // Thickness of the line
    private static final int STEP = 1;           // Distance between segments

    static final int MINIMUM_BAR_HEIGHT = 1;
    static final int MAXIMUM_BAR_HEIGHT = 30;

    private static final int INDENT_WIDTH_ONE_SIDE = 4; // The skill panel from OSRS indents 3 pixels at the bottom (and top)

    private static final int WIDGET_CHILD_ID_MASK = 0xFFFF;

    private static final Set<Skill> SKILLS_F2P = Set.of(
            Skill.ATTACK,
            Skill.STRENGTH,
            Skill.DEFENCE,
            Skill.RANGED,
            Skill.PRAYER,
            Skill.MAGIC,
            Skill.RUNECRAFT,
            Skill.HITPOINTS,
            Skill.CRAFTING,
            Skill.MINING,
            Skill.SMITHING,
            Skill.FISHING,
            Skill.COOKING,
            Skill.FIREMAKING,
            Skill.WOODCUTTING
    );

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private SkillsTabProgressBarsConfig config;

    private Widget currentWidget;
    private SkillData lastSkillBuiltBarsFor;
    private SkillBarWidgetGrouping currentHovered;
    private SkillBarWidgetGrouping[] skillBars = new SkillBarWidgetGrouping[SkillData.values().length];

    private float[] progressStartHSB;
    private float[] progressEndHSB;
    private float[] goalStartHSB;
    private float[] goalEndHSB;

    @Override
    protected void startUp() {
        if (client.getGameState() == GameState.LOGGED_IN) {
            clientThread.invoke(this::buildSkillBars);
        }
    }

    @Override
    protected void shutDown() {
        clientThread.invoke(this::removeSkillBars);
    }

    @Provides
    SkillsTabProgressBarsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(SkillsTabProgressBarsConfig.class);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (SkillsTabProgressBarsConfig.GROUP.equals(event.getGroup())) {
            // Clear out the cached HSBAs when they change, so the getters can regenerate them.
            switch (event.getKey()) {
                case "progressBarStartColor":
                    progressStartHSB = null;
                    break;
                case "progressBarEndColor":
                    progressEndHSB = null;
                    break;
                case "goalBarStartColor":
                    goalStartHSB = null;
                    break;
                case "goalBarEndColor":
                    goalEndHSB = null;
                    break;
                case "showOnHover":
                    handleContainerListener();
                    break;
            }
            // Force an update to bar size and colours
            updateSkillBars();
        }
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event) {
        if (event.getScriptId() == SCRIPTID_STATS_INIT || event.getScriptId() == SCRIPTID_STATS_REFRESH) {
            Widget widget = event.getScriptEvent().getSource();
            if (widget.getId() == InterfaceID.Stats.UNIVERSE) {
                currentWidget = null;
            } else {
                currentWidget = widget;
            }
        }
    }

    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (
                (event.getScriptId() == SCRIPTID_STATS_INIT || event.getScriptId() == SCRIPTID_STATS_REFRESH)
                        && currentWidget != null
        ) {
            buildSkillBar(currentWidget);
        }
        // Add the container listener after all the other bars have been created
        // There's no specific reason to do it after, but this will always fire once on creation of the skills tab
        else if (event.getScriptId() == SCRIPTID_STATS_SKILLTOTAL) {
            handleContainerListener();
        }
    }

    /**
     * If the plugin is started after the skill panel has been built, this will add the bar widgets that are needed.
     */
    private void buildSkillBars() {
        Widget skillsContainer = client.getWidget(InterfaceID.Stats.UNIVERSE);
        if (skillsContainer == null) {
            return;
        }

        for (Widget skillTile : skillsContainer.getStaticChildren()) {
            buildSkillBar(skillTile);
        }
        handleContainerListener();
    }

    private void removeSkillBars() {
        for (SkillBarWidgetGrouping grouping : skillBars) {
            if (grouping == null) {
                continue;
            }
            Widget parent = grouping.getPrimaryBarBackground().getParent();
            removeHoverListener(parent, grouping);
            Widget[] children = parent.getChildren();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    Widget child = children[i];
                    if (grouping.contains(child)) {
                        children[i] = null;
                    }
                }
            }
        }
        removeContainerListener();
        skillBars = new SkillBarWidgetGrouping[SkillData.values().length];
    }

    private int getChildId(int id) {
        return id & WIDGET_CHILD_ID_MASK;
    }

    /**
     * Create the widgets needed for the bars to exist, and keep a reference to them
     * Setting their position, size, and colour is done in {@link #updateSkillBar}
     *
     * @param parent The parent widget inside which the skill bar is created
     */
    private void buildSkillBar(Widget parent) {
        if (parent.getType() != WidgetType.LAYER) {
            log.error("buildSkillBar called with non-layer widget");
            return;
        }

        int idx = getChildId(parent.getId()) - 1;
        SkillData skill = SkillData.get(idx);
        if (skill == null) {
            return;
        }

        // Had to change to separate init and refresh scripts for sailing because of Jagex changes.
        // init script that fires twice per skill. Avoid building twice the amount of bars.
        if (skill == lastSkillBuiltBarsFor) {
            return;
        }
        lastSkillBuiltBarsFor = skill;

        // Since sailing pre-release with the new total level full-width bar, all skills had their widgets height changed to 30, except the bottom three skills remained at 32.
        // This ultimately resulted in bars on those bottom three skills of height 1 or 2 being hidden behind the total level bar,
        // and bars of greater height still appearing shorter than the bars on the rest of the skills.
        // By resetting their heights back to maximum bar height (which itself needed changing for this update), they appear normally.
        if (skill == SkillData.CONSTRUCTION || skill == SkillData.HUNTER || skill == SkillData.SAILING) {
            parent.setOriginalHeight(MAXIMUM_BAR_HEIGHT);
            parent.revalidate();
        }

        Widget grayOut99 = parent.createChild(-1, WidgetType.RECTANGLE);
        grayOut99.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
        grayOut99.setYPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
        grayOut99.setWidthMode(WidgetSizeMode.MINUS);
        grayOut99.setHeightMode(WidgetSizeMode.MINUS);
        grayOut99.setOriginalWidth(0);
        grayOut99.setOriginalHeight(0);
        grayOut99.setFilled(true);
        grayOut99.setHasListener(true);
        grayOut99.setTextColor(Color.BLACK.getRGB());

        Widget primaryBarBackground = parent.createChild(-1, WidgetType.RECTANGLE);
        primaryBarBackground.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
        primaryBarBackground.setWidthMode(WidgetSizeMode.MINUS);
        primaryBarBackground.setFilled(true);
        primaryBarBackground.setHasListener(true);
        Widget primaryBarForeground = parent.createChild(-1, WidgetType.RECTANGLE);
        primaryBarForeground.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
        primaryBarForeground.setFilled(true);
        primaryBarForeground.setHasListener(true);

        Widget secondaryBarBackground = parent.createChild(-1, WidgetType.RECTANGLE);
        secondaryBarBackground.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
        secondaryBarBackground.setWidthMode(WidgetSizeMode.MINUS);
        secondaryBarBackground.setFilled(true);
        secondaryBarBackground.setHasListener(true);
        Widget secondaryBarForeground = parent.createChild(-1, WidgetType.RECTANGLE);
        secondaryBarForeground.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
        secondaryBarForeground.setFilled(true);
        secondaryBarForeground.setHasListener(true);

        Widget skillDividerBarContainer = parent.createChild(-1, WidgetType.LAYER);
        skillDividerBarContainer.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
        skillDividerBarContainer.setFilled(false);
        skillDividerBarContainer.setHasListener(true);
        skillDividerBarContainer.setOriginalX(0);
        skillDividerBarContainer.setOriginalY(0);
        skillDividerBarContainer.setOriginalWidth(62);
        skillDividerBarContainer.setOriginalHeight(30);
        Widget[] skillDividerBarSegments = createProgressBarSegments(skillDividerBarContainer);

        SkillBarWidgetGrouping grouping = new SkillBarWidgetGrouping(grayOut99, primaryBarBackground, primaryBarForeground, secondaryBarBackground, secondaryBarForeground, skillDividerBarContainer, skillDividerBarSegments);

        JavaScriptCallback updateCallback = ev -> updateSkillBar(skill, grouping);

        for (Widget widget : grouping.all()) {
            widget.setOnVarTransmitListener(updateCallback);
        }

        updateSkillBar(skill, grouping);
        handleHoverListener(parent, grouping);

        // Actively remove previous bars. Required as otherwise the bars built by init script overlap those by
        // refresh script, so clean them up before we throw away the references.
        if (skillBars[idx] != null) {
            SkillBarWidgetGrouping groupingToRemove = skillBars[idx];
            Widget[] children = parent.getChildren();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    if (groupingToRemove.contains(children[i])) {
                        children[i] = null;
                    }
                }
                parent.setChildren(children);
            }
        }

        skillBars[idx] = grouping;
    }

    private Widget[] createProgressBarSegments(Widget parent)
    {
        int numberOfSegments = CONTAINER_SIZE / STEP;
        Widget[] segmentArray = new Widget[numberOfSegments];

        for (int i = 0; i < numberOfSegments; i++)
        {
            Widget segment = parent.createChild(-1, WidgetType.RECTANGLE);
            segment.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
            segment.setYPositionMode(WidgetPositionMode.ABSOLUTE_BOTTOM);
            segmentArray[i] = segment;
        }

        return segmentArray;
    }

    private void updateProgressBarSegments(Widget[] progressBarSegments, double progressPercent, int colorRgb)
    {
        int numberOfSegments = progressBarSegments.length;
        int numberOfFilledSegments = (int) Math.round((progressPercent) * numberOfSegments);

        for (int i = 0; i < numberOfSegments; i++)
        {
            Widget segment = progressBarSegments[i];

            // 45 degree diagonal
            int xPos = 34 + (i * STEP);
            int yPos = 4 + (i * STEP);
            segment.setOriginalX(xPos);
            segment.setOriginalY(yPos);

            if (i < numberOfFilledSegments) {
                segment.setFilled(true);
                segment.setTextColor(colorRgb);
                segment.setOriginalWidth(SEGMENT_SIZE);
                segment.setOriginalHeight(SEGMENT_SIZE);
            } else {
                segment.setFilled(false);
                segment.setOriginalWidth(0);
                segment.setOriginalHeight(0);
            }
        }
    }

    /**
     * Add or remove hover listeners on the provided widget.
     *
     * @param parent   The widget containing the skill information and bars
     * @param grouping The collection of widgets representing the bars
     */
    private void handleHoverListener(Widget parent, SkillBarWidgetGrouping grouping) {
        if (config.showOnHover()) {
            addHoverListener(parent, grouping);
        } else {
            removeHoverListener(parent, grouping);
        }
    }

    /**
     * See {@link #handleHoverListener}
     */
    private void addHoverListener(Widget parent, SkillBarWidgetGrouping grouping) {
        Widget[] groupWidgets = grouping.all();

        for (Widget widget : groupWidgets) {
            widget.setHidden(true);
        }

        parent.setOnMouseOverListener((JavaScriptCallback) ev -> {
            // We need to hide the old hovered widgets so there aren't multiple visible
            // when moving the mouse between skills.
            if (currentHovered != null) {
                for (Widget widget : currentHovered.all()) {
                    widget.setHidden(true);
                }
            }

            currentHovered = grouping;
            for (Widget widget : groupWidgets) {
                widget.setHidden(false);
            }
        });

        parent.setHasListener(true);
    }

    /**
     * See {@link #handleHoverListener}
     */
    private void removeHoverListener(Widget parent, SkillBarWidgetGrouping grouping) {
        for (Widget widget : grouping.all()) {
            widget.setHidden(false);
        }

        parent.setOnMouseOverListener((Object[]) null);
    }

    /**
     * Add or remove a listener to hide the currently visible bar if needed.
     * This needs to be added to the container as each of the skills use {@link Widget#setOnMouseLeaveListener}
     * to handle the vanilla tooltip destruction.
     */
    private void handleContainerListener() {
        if (config.showOnHover()) {
            addContainerListener();
        } else {
            removeContainerListener();
        }
    }

    /**
     * See {@link #handleContainerListener}
     */
    private void addContainerListener() {
        Widget container = client.getWidget(InterfaceID.Stats.UNIVERSE);
        if (container == null) {
            return;
        }

        container.setOnMouseLeaveListener((JavaScriptCallback) ev -> {
            if (currentHovered != null) {
                for (Widget widget : currentHovered.all()) {
                    widget.setHidden(true);
                }
            }
            currentHovered = null;
        });
        container.setHasListener(true);
    }

    /**
     * See {@link #handleContainerListener}
     */
    private void removeContainerListener() {
        Widget container = client.getWidget(InterfaceID.Stats.UNIVERSE);
        if (container == null) {
            return;
        }
        container.setOnMouseLeaveListener((Object[]) null);
    }

    /**
     * Update all the skill bars that we're currently using, in the case that the config was changed.
     */
    private void updateSkillBars() {
        clientThread.invoke(() -> {
            for (int i = 0; i < SkillData.values().length; i++) {
                SkillData skill = SkillData.get(i);
                SkillBarWidgetGrouping widgets = skillBars[i];
                if (skill != null && widgets != null) {
                    updateSkillBar(skill, widgets);
                    handleHoverListener(widgets.getPrimaryBarBackground().getParent(), widgets);
                }
            }
        });
    }

    private boolean shouldDarken(Skill skill, int currentLevel, int currentXP) {
        if (config.darkenMembersSkills() && !SKILLS_F2P.contains(skill))
            return true;

        switch (config.darkenType()) {
            case None:
                return false;
            case Level99:
                return currentLevel >= Experience.MAX_REAL_LEVEL;
            case LevelCustom:
                return currentLevel >= config.darkenCustomLevel();
            case XP200m:
                return currentXP >= Experience.MAX_SKILL_XP;
            case XPCustom:
                return currentXP >= config.darkenCustomXP();
        }

        return false;
    }

    /**
     * Update a specific skill's bar
     *
     * @param skill    The skill to be updated
     * @param grouping The collection of widgets to represent the progress and goal bars
     */
    private void updateSkillBar(SkillData skill, SkillBarWidgetGrouping grouping) {
        Widget grayOut99 = grouping.getGrayOut99();
        Widget primaryBarBackground = grouping.getPrimaryBarBackground();
        Widget primaryBarForeground = grouping.getPrimaryBarForeground();
        Widget secondaryBarBackground = grouping.getSecondaryBarBackground();
        Widget secondaryBarForeground = grouping.getSecondaryBarForeground();
        Widget skillDividerBarContainer = grouping.getSkillDividerBarContainer();
        Widget[] skillDividerBarSegments = grouping.getSkillDividerBarSegments();

        final int currentXP = client.getSkillExperience(skill.getSkill());
        final int currentLevel = Experience.getLevelForXp(currentXP);
        final int currentLevelXP = Experience.getXpForLevel(currentLevel);
        final int nextLevelXP = currentLevel >= Experience.MAX_VIRT_LEVEL
                ? Experience.MAX_SKILL_XP
                : Experience.getXpForLevel(currentLevel + 1);

        final int goalStartXP = client.getVarpValue(skill.getGoalStartVarp());
        final int goalEndXP = client.getVarpValue(skill.getGoalEndVarp());

        final boolean shouldGrayOut = shouldDarken(skill.getSkill(), currentLevel, currentXP);
        if (shouldGrayOut) {
            grayOut99.setOpacity(255 - config.darkenOpacity());
        } else {
            // Set the gray out to be invisible so it doesn't conflict with the hover hiding
            grayOut99.setOpacity(255);
        }

        final boolean shouldRenderAnyBars = !config.showOnHover() || grouping == currentHovered;
        if (!shouldRenderAnyBars) {
            for (Widget widget : grouping.all()) {
                widget.setHidden(true);
            }
            return;
        }

        final boolean shouldCalculateXpBar =
                !config.showOnlyGoals() &&
                        (currentLevel < Experience.MAX_REAL_LEVEL || config.virtualLevels()) &&
                        (currentXP < Experience.MAX_SKILL_XP || config.stillShowAt200m()) &&
                        (!shouldGrayOut || !config.hideProgressBarWhenDarkened());
        final boolean shouldCalculateGoalBar =
                goalEndXP > 0 &&
                        config.showGoals() &&
                        (!shouldGrayOut || !config.hideGoalBarWhenDarkened());

        BarType xpBarType = null;
        BarType goalBarType = null;

        if (!config.showOnlyGoals() && shouldCalculateXpBar) {
            xpBarType = config.useSkillDividerAsProgressBar() ? BarType.SKILL_DIVIDER : BarType.PRIMARY;
        }

        if (shouldCalculateGoalBar) {
            if (config.showOnlyGoals()) {
                goalBarType = config.useSkillDividerAsProgressBar()
                        ? BarType.SKILL_DIVIDER
                        : BarType.PRIMARY;
            } else if (config.useSkillDividerAsProgressBar()) {
                goalBarType = BarType.PRIMARY;
            } else {
                goalBarType = xpBarType == null ? BarType.PRIMARY : BarType.SECONDARY;
            }
        }

        int barHeight = config.barHeight();
        if (!config.useSkillDividerAsProgressBar() && goalBarType == BarType.SECONDARY && barHeight > MAXIMUM_BAR_HEIGHT / 2)
        {
            barHeight /= 2;
        }

        EnumSet<BarType> usedBarTypes = EnumSet.noneOf(BarType.class);
        if (xpBarType != null)
        {
            usedBarTypes.add(xpBarType);
        }
        if (goalBarType != null)
        {
            usedBarTypes.add(goalBarType);
        }

        if (!usedBarTypes.contains(BarType.SKILL_DIVIDER))
        {
            skillDividerBarContainer.setOpacity(255);
            for (Widget segment : skillDividerBarSegments) {
                segment.setOpacity(255);
            }
        }

        if (!usedBarTypes.contains(BarType.PRIMARY))
        {
            primaryBarBackground.setOpacity(255);
            primaryBarForeground.setOpacity(255);
        }

        if (!usedBarTypes.contains(BarType.SECONDARY))
        {
            secondaryBarBackground.setOpacity(255);
            secondaryBarForeground.setOpacity(255);
        }

        if (xpBarType != null) {
            final double xpPercent = Math.min(1.0, (currentXP - currentLevelXP) / (double) (nextLevelXP - currentLevelXP));
            float[] startHsb = this.getProgressStartHSB();
            float[] endHsb = this.getProgressEndHSB();
            Color startColor = config.progressBarStartColor();
            Color endColor = config.progressBarEndColor();

            if (xpBarType == BarType.SKILL_DIVIDER) {
                this.setSkillDividerBar(skillDividerBarSegments, xpPercent, startHsb, endHsb, startColor, endColor);
            } else {
                this.setStandardBar(primaryBarBackground, primaryBarForeground, 0, barHeight, xpPercent, startHsb, endHsb, startColor, endColor);
            }
        }

        if (goalBarType != null) {
            final double goalPercent = Math.min(1.0, (currentXP - goalStartXP) / (double) (goalEndXP - goalStartXP));
            float[] startHsb = this.getGoalStartHSB();
            float[] endHsb = this.getGoalEndHSB();
            Color startColor = config.goalBarStartColor();
            Color endColor = config.goalBarEndColor();


            if (goalBarType == BarType.SKILL_DIVIDER) {
                this.setSkillDividerBar(skillDividerBarSegments, goalPercent, startHsb, endHsb, startColor, endColor);
            } else if (goalBarType == BarType.PRIMARY) {
                this.setStandardBar(primaryBarBackground, primaryBarForeground, 0, barHeight, goalPercent, startHsb, endHsb, startColor, endColor);
            } else {
                this.setStandardBar(secondaryBarBackground, secondaryBarForeground, barHeight, barHeight, goalPercent, startHsb, endHsb, startColor, endColor);
            }

        }

        for (Widget widget : grouping.all()) {
            widget.revalidate();
        }
    }

    private void setStandardBar(Widget barBackground, Widget barForeground, int yPos, int barHeight, double percent, float[] startHsb, float[] endHsb, Color startColor, Color endColor) {
        barForeground.setHidden(false);
        barBackground.setHidden(false);

        final int startX = config.indent() ? INDENT_WIDTH_ONE_SIDE : 0;
        int maxWidth = barForeground.getParent().getOriginalWidth();
        if (config.indent()) {
            maxWidth -= INDENT_WIDTH_ONE_SIDE * 2;
        }

        barBackground.setOriginalX(startX);
        barBackground.setOriginalY(yPos);
        barBackground.setOriginalWidth(config.indent() ? INDENT_WIDTH_ONE_SIDE * 2 : 0);
        barBackground.setOriginalHeight(barHeight);
        barBackground.setTextColor(config.backgroundColor().getRGB());
        barBackground.setOpacity(255 - config.backgroundColor().getAlpha());

        final int progressWidth = (int) (maxWidth * percent);

        barForeground.setOriginalX(startX);
        barForeground.setOriginalY(yPos);
        barForeground.setOriginalWidth(progressWidth);
        barForeground.setOriginalHeight(barHeight);
        barForeground.setTextColor(lerpHSB(startHsb, endHsb, percent)); // interpolate between start and end
        barForeground.setOpacity(255 - lerpAlpha(startColor, endColor, percent));
    }

    private void setSkillDividerBar(Widget[] segments, double percent, float[] startHsb, float[] endHsb, Color startColor, Color endColor) {
        final int progressColor = lerpHSB(startHsb, endHsb, percent);
        final int progressOpacity = 255 - lerpAlpha(startColor, endColor, percent);
        for (Widget progressBarSegment : segments) {
            progressBarSegment.setOpacity(progressOpacity);
        }

        this.updateProgressBarSegments(segments, percent, progressColor);
    }

    /**
     * Linearly interpolate between two colours in HSB arrays
     *
     * @param start   The starting colour, as a HSB array
     * @param end     The ending colour, as a HSB array
     * @param percent Tow much of the ending colour to include as a percentage
     * @return The integer representation of the interpolated colour's RGB value
     */
    private int lerpHSB(float[] start, float[] end, double percent) {
        return Color.getHSBColor(
                (float) (start[0] + percent * (end[0] - start[0])),
                (float) (start[1] + percent * (end[1] - start[1])),
                (float) (start[2] + percent * (end[2] - start[2]))).getRGB();
    }

    /**
     * Linearly interpolate between the alpha values of two colours
     *
     * @param start   The starting colour
     * @param end     The ending colour
     * @param percent how much of the ending colour to include as a percentage
     * @return The interpolated alpha value
     */
    private int lerpAlpha(Color start, Color end, double percent) {
        return (int) Math.round(start.getAlpha() + (percent * (end.getAlpha() - start.getAlpha())));
    }

    /**
     * Convert the starting colour of the progress bar into a HSB array, with caching
     */
    private float[] getProgressStartHSB() {
        if (progressStartHSB == null) {
            progressStartHSB = getHSBArray(config.progressBarStartColor());
        }
        return progressStartHSB;
    }

    /**
     * Convert the starting colour of the progress bar into a HSB array, with caching
     */
    private float[] getProgressEndHSB() {
        if (progressEndHSB == null) {
            progressEndHSB = getHSBArray(config.progressBarEndColor());
        }
        return progressEndHSB;
    }

    /**
     * Convert the starting colour of the progress bar into a HSB array, with caching
     */
    private float[] getGoalStartHSB() {
        if (goalStartHSB == null) {
            goalStartHSB = getHSBArray(config.goalBarStartColor());
        }
        return goalStartHSB;
    }

    /**
     * Convert the starting colour of the progress bar into a HSB array, with caching
     */
    private float[] getGoalEndHSB() {
        if (goalEndHSB == null) {
            goalEndHSB = getHSBArray(config.goalBarEndColor());
        }
        return goalEndHSB;
    }

    /**
     * @param color The colour to convert
     * @return The passed colour as a HSB array
     */
    private float[] getHSBArray(Color color) {
        float[] arr = new float[3];
        Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), arr);
        return arr;
    }
}
