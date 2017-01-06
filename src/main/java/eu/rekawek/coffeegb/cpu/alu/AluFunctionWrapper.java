package eu.rekawek.coffeegb.cpu.alu;

import eu.rekawek.coffeegb.cpu.Flags;
import eu.rekawek.coffeegb.cpu.op.DataType;

import java.util.HashMap;
import java.util.Map;

public class AluFunctionWrapper {

    private Map<FunctionKey, IntRegistryFunction> functions = new HashMap<>();

    private Map<FunctionKey, BiIntRegistryFunction> biFunctions = new HashMap<>();

    void registerAluFunction(String name, DataType dataType, IntRegistryFunction function) {
        functions.put(new FunctionKey(name, dataType), function);
    }

    void registerAluFunction(String name, DataType dataType1, DataType dataType2, BiIntRegistryFunction function) {
        biFunctions.put(new FunctionKey(name, dataType1, dataType2), function);
    }

    public IntRegistryFunction findAluFunction(String name, DataType argumentType) {
        return functions.get(new FunctionKey(name, argumentType));

    }

    public BiIntRegistryFunction findAluFunction(String name, DataType arg1Type, DataType arg2Type) {
        return biFunctions.get(new FunctionKey(name, arg1Type, arg2Type));
    }

    private static class FunctionKey {

        private final String name;

        private final DataType type1;

        private final DataType type2;

        public FunctionKey(String name, DataType type1, DataType type2) {
            this.name = name;
            this.type1 = type1;
            this.type2 = type2;
        }

        public FunctionKey(String name, DataType type) {
            this.name = name;
            this.type1 = type;
            this.type2 = null;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            FunctionKey that = (FunctionKey) o;

            if (!name.equals(that.name)) return false;
            if (!type1.equals(that.type1)) return false;
            return type2 != null ? type2.equals(that.type2) : that.type2 == null;
        }

        @Override
        public int hashCode() {
            int result = name.hashCode();
            result = 31 * result + type1.hashCode();
            result = 31 * result + (type2 != null ? type2.hashCode() : 0);
            return result;
        }
    }

    public interface IntRegistryFunction {
        int apply(Flags flags, int arg);
    }

    public interface BiIntRegistryFunction {
        int apply(Flags flags, int arg1, int arg2);
    }
}
