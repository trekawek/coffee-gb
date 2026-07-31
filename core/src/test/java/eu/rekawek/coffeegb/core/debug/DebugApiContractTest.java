package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterType;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugInterruptCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugMemoryCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugOpcodeCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPpuCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugSerialCondition;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryCapabilities;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPoint;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPosition;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason;
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult;
import eu.rekawek.coffeegb.core.debug.trace.ApuTrace;
import eu.rekawek.coffeegb.core.debug.trace.CpuInstructionTrace;
import eu.rekawek.coffeegb.core.debug.trace.DmaTrace;
import eu.rekawek.coffeegb.core.debug.trace.InputTrace;
import eu.rekawek.coffeegb.core.debug.trace.InterruptTrace;
import eu.rekawek.coffeegb.core.debug.trace.MapperRtcTrace;
import eu.rekawek.coffeegb.core.debug.trace.MemoryAccessTrace;
import eu.rekawek.coffeegb.core.debug.trace.PpuTrace;
import eu.rekawek.coffeegb.core.debug.trace.SerialIrTrace;
import eu.rekawek.coffeegb.core.debug.trace.TimerTrace;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceEntry;
import eu.rekawek.coffeegb.core.debug.trace.TraceEvent;
import eu.rekawek.coffeegb.core.debug.trace.TraceFilter;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult;
import eu.rekawek.coffeegb.core.debug.trace.TraceSource;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DebugApiContractTest {

    private static final Set<Class<?>> API_TYPES = Set.of(
            DebugAddressSpace.class,
            DebugAudioChannelInspection.class,
            DebugAudioInspection.class,
            DebugApuState.class,
            DebugButton.class,
            DebugBreakpoint.class,
            DebugBreakpointCondition.class,
            DebugBreakpointHit.class,
            DebugBreakpointId.class,
            DebugBreakpointKind.class,
            DebugBreakpointList.class,
            DebugByteData.class,
            DebugCapabilities.class,
            DebugCounterCondition.class,
            DebugCounterType.class,
            DebugCpuState.class,
            DebugError.class,
            DebugErrorCode.class,
            DebugExecutionState.class,
            DebugFeatureState.class,
            DebugHistoryCapabilities.class,
            DebugHistoryConfiguration.class,
            DebugHistoryPoint.class,
            DebugHistoryPosition.class,
            DebugHistoryStatus.class,
            DebugHistoryTruncationReason.class,
            DebugAnchoredMemoryRequest.class,
            DebugDisassembler.class,
            DebugGraphicsHardwareMode.class,
            DebugGraphicsInspection.class,
            DebugHardwareInspection.class,
            DebugHardwareInspection.Joypad.class,
            DebugHardwareInspection.Serial.class,
            DebugHardwareInspection.Infrared.class,
            DebugHardwareInspection.OamDma.class,
            DebugHardwareInspection.VramDma.class,
            DebugHardwareInspection.System.class,
            DebugInspectionAnchor.class,
            DebugInspectionRequest.class,
            DebugInspectionResult.class,
            DebugInspectionSection.class,
            DebugInterruptCondition.class,
            DebugInterruptState.class,
            DebugInterruptType.class,
            DebugMapperState.class,
            DebugMemoryBlock.class,
            DebugMemoryCondition.class,
            DebugMemoryAccess.class,
            DebugMemoryRequest.class,
            DebugOpcodeCondition.class,
            DebugPcCondition.class,
            DebugPort.class,
            DebugPpuCondition.class,
            DebugSerialCondition.class,
            DebugSerialCondition.Event.class,
            DebugPpuMode.class,
            DebugPpuState.class,
            DebugRegisters.class,
            DebugResult.class,
            DebugReverseStepResult.class,
            DebugSnapshot.class,
            DebugStepKind.class,
            DebugStepResult.class,
            DebugStepStopReason.class,
            DebugTimerState.class,
            ApuTrace.class,
            CpuInstructionTrace.class,
            DmaTrace.class,
            InputTrace.class,
            InterruptTrace.class,
            MapperRtcTrace.class,
            MemoryAccessTrace.class,
            PpuTrace.class,
            SerialIrTrace.class,
            TimerTrace.class,
            TraceCategory.class,
            TraceConfiguration.class,
            TraceEntry.class,
            TraceEvent.class,
            TraceFilter.class,
            TraceReadRequest.class,
            TraceReadResult.class,
            TraceSource.class);

    @Test
    public void portHasThePinnedAsynchronousSessionBoundSurface() throws Exception {
        assertEquals(long.class, DebugPort.class.getMethod("sessionGeneration").getReturnType());
        assertEquals(DebugCapabilities.class,
                DebugPort.class.getMethod("capabilities").getReturnType());
        assertEquals(boolean.class, DebugPort.class.getMethod("isClosed").getReturnType());
        assertEquals(void.class, DebugPort.class.getMethod("close").getReturnType());

        assertReturnType("pause",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.DebugSnapshot>>");
        assertReturnType("resume",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.DebugSnapshot>>");
        assertReturnType("snapshot",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.DebugSnapshot>>");
        assertReturnType("inspect",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.DebugInspectionResult>>",
                DebugInspectionRequest.class);
        assertReturnType("step",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.DebugStepResult>>",
                DebugStepKind.class);
        assertReturnType("configureHistory",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus>>",
                DebugHistoryConfiguration.class);
        assertReturnType("historyStatus",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus>>");
        assertReturnType("stepBackward",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult>>",
                DebugStepKind.class);
        assertReturnType("readMemory",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.DebugMemoryBlock>>",
                DebugMemoryRequest.class);
        assertReturnType("setButton",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<java.lang.Void>>",
                DebugButton.class, boolean.class);
        assertReturnType("setBreakpoint",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint>>",
                DebugBreakpoint.class);
        assertReturnType("removeBreakpoint",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<java.lang.Void>>",
                DebugBreakpointId.class);
        assertReturnType("listBreakpoints",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.DebugBreakpointList>>");
        assertReturnType("lastBreakpointHit",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.DebugBreakpointHit>>");
        assertReturnType("configureTrace",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration>>",
                TraceConfiguration.class);
        assertReturnType("readTrace",
                "java.util.concurrent.CompletionStage<eu.rekawek.coffeegb.core.debug.DebugResult"
                        + "<eu.rekawek.coffeegb.core.debug.trace.TraceReadResult>>",
                TraceReadRequest.class);
    }

    @Test
    public void dtoSurfaceIsFinalAndDoesNotExposeMutableArrays() {
        for (Class<?> type : API_TYPES) {
            if (!type.isInterface()) {
                assertTrue(type.getName(), Modifier.isFinal(type.getModifiers()));
            }
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) continue;
                assertTrue(type.getName() + "." + field.getName(),
                        Modifier.isPrivate(field.getModifiers()));
                assertTrue(type.getName() + "." + field.getName(),
                        Modifier.isFinal(field.getModifiers()));
            }
            for (Method method : type.getMethods()) {
                if (!API_TYPES.contains(method.getDeclaringClass())) continue;
                // Every Java enum necessarily has the compiler-generated values() array factory.
                if (type.isEnum() && method.getName().equals("values")
                        && method.getParameterCount() == 0) continue;
                assertFalse(type.getName() + "." + method.getName(),
                        method.getReturnType().isArray());
                assertAllowed(method.getGenericReturnType(), type + "." + method.getName());
                for (Type parameter : method.getGenericParameterTypes()) {
                    assertAllowed(parameter, type + "." + method.getName());
                }
            }
        }
    }

    @Test
    public void onlyOwnedBytePayloadsAcceptArraysAndNoneReturnOne() {
        List<Constructor<?>> arrayConstructors = API_TYPES.stream()
                .flatMap(type -> List.of(type.getConstructors()).stream())
                .filter(constructor -> List.of(constructor.getParameterTypes()).stream()
                        .anyMatch(Class::isArray))
                .collect(Collectors.toList());
        assertEquals(Set.of(DebugByteData.class, DebugMemoryBlock.class),
                arrayConstructors.stream().map(Constructor::getDeclaringClass)
                        .collect(Collectors.toSet()));
    }

    @Test
    public void errorCodesArePinnedForMachineReadableClients() {
        assertEquals(List.of(
                        "NO_ACTIVE_SESSION", "SESSION_REPLACED", "PORT_CLOSED", "QUEUE_FULL",
                        "INVALID_ARGUMENT", "NOT_PAUSED", "ALREADY_PAUSED", "ALREADY_RUNNING",
                        "CPU_IDLE", "CPU_LOCKED", "UNSUPPORTED_STEP",
                        "UNSUPPORTED_ADDRESS_SPACE", "SIDE_EFFECTFUL_ADDRESS",
                        "UNSUPPORTED_TOPOLOGY", "SESSION_BUSY", "STEP_LIMIT",
                        "BREAKPOINT_LIMIT", "BREAKPOINT_NOT_FOUND", "NO_BREAKPOINT_HIT",
                        "UNSUPPORTED_BREAKPOINT", "UNSUPPORTED_TRACE_CATEGORY", "TRACE_LIMIT",
                        "INTERNAL_ERROR", "HISTORY_DISABLED", "HISTORY_EXHAUSTED",
                        "HISTORY_LIMIT"),
                List.of(DebugErrorCode.values()).stream().map(Enum::name)
                        .collect(Collectors.toList()));
    }

    private static void assertReturnType(String method, String expected, Class<?>... parameters)
            throws Exception {
        assertEquals(expected,
                DebugPort.class.getMethod(method, parameters).getGenericReturnType().getTypeName());
    }

    private static void assertAllowed(Type type, String location) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) {
                assertTrue(location, clazz == byte[].class);
                return;
            }
            if (clazz.isPrimitive() || clazz == String.class || clazz == Void.class
                    || clazz == CompletionStage.class || clazz == AutoCloseable.class
                    || clazz == Class.class || clazz == Object.class
                    || clazz == List.class || clazz == Optional.class || clazz == Set.class
                    || clazz.getPackageName().equals("eu.rekawek.coffeegb.core.debug")
                    || clazz.getPackageName().startsWith("eu.rekawek.coffeegb.core.debug.")) {
                return;
            }
            throw new AssertionError(location + " exposes " + clazz.getName());
        }
        if (type instanceof ParameterizedType parameterized) {
            assertAllowed(parameterized.getRawType(), location);
            for (Type argument : parameterized.getActualTypeArguments()) {
                assertAllowed(argument, location);
            }
            return;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) assertAllowed(bound, location);
            for (Type bound : wildcard.getLowerBounds()) assertAllowed(bound, location);
            return;
        }
        if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) assertAllowed(bound, location);
            return;
        }
        throw new AssertionError(location + " exposes unsupported type " + type.getTypeName());
    }
}
