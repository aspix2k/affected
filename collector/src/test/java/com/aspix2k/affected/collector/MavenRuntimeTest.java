package com.aspix2k.affected.collector;

import com.aspix2k.affected.collector.maven.AffectedMavenLifecycleParticipant;
import com.aspix2k.affected.collector.maven.MavenRuntime;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MavenRuntimeTest {

    @Test
    public void maven39StableRuntimesAreExactEligible() {
        assertEquals(MavenRuntime.Line.MAVEN_39_EXACT, MavenRuntime.classify("3.9.0"));
        assertEquals(MavenRuntime.Line.MAVEN_39_EXACT, MavenRuntime.classify("3.9.16"));
        assertTrue(MavenRuntime.exactEligible("3.9.0"));
        assertTrue(AffectedMavenLifecycleParticipant.supportedRuntime("3.9.16"));
    }

    @Test
    public void maven4PreviewRuntimesKeepTheFullGoal() {
        assertEquals(MavenRuntime.Line.MAVEN_4_PREVIEW, MavenRuntime.classify("4.0.0-rc-6"));
        assertEquals(MavenRuntime.Line.MAVEN_4_PREVIEW, MavenRuntime.classify("4.0.0-beta-4"));
        assertEquals(MavenRuntime.Line.MAVEN_4_PREVIEW, MavenRuntime.classify("4.0.0-alpha-1"));
        assertEquals(MavenRuntime.Line.MAVEN_4_PREVIEW, MavenRuntime.classify("4.0.0-SNAPSHOT"));
        assertFalse(MavenRuntime.exactEligible("4.0.0-rc-6"));
        assertFalse(AffectedMavenLifecycleParticipant.supportedRuntime("4.0.0-rc-6"));
    }

    @Test
    public void maven4GaHasNoProvenExactRangeYet() {
        assertEquals(MavenRuntime.Line.UNSUPPORTED, MavenRuntime.classify("4.0.0"));
        assertFalse(MavenRuntime.exactEligible("4.0.0"));
        assertFalse(AffectedMavenLifecycleParticipant.supportedRuntime("4.0.0"));
    }

    @Test
    public void unknownOrMalformedRuntimesAreUnsupported() {
        assertEquals(MavenRuntime.Line.UNSUPPORTED, MavenRuntime.classify(null));
        assertEquals(MavenRuntime.Line.UNSUPPORTED, MavenRuntime.classify(""));
        assertEquals(MavenRuntime.Line.UNSUPPORTED, MavenRuntime.classify("3.8.9"));
        assertEquals(MavenRuntime.Line.UNSUPPORTED, MavenRuntime.classify("3.9.0-rc-1"));
        assertEquals(MavenRuntime.Line.UNSUPPORTED, MavenRuntime.classify("3.10.0"));
        assertEquals(MavenRuntime.Line.UNSUPPORTED, MavenRuntime.classify("5.0.0"));
        assertFalse(AffectedMavenLifecycleParticipant.supportedRuntime(null));
    }
}
