using System.Collections.Immutable;
using System.Reflection;
using System.Reflection.Emit;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;

if (args.Length != 2)
{
    return 2;
}

try
{
    var requestPath = Path.GetFullPath(args[0]);
    var responsePath = Path.GetFullPath(args[1]);
    if (new FileInfo(requestPath).Length > 1024 * 1024)
    {
        return 3;
    }

    var request = JsonSerializer.Deserialize(File.ReadAllBytes(requestPath), JsonOptions.Default.AnalyzerRequest);
    if (request is null || request.Schema != 3 || request.Artifacts.Count == 0 ||
        request.ProductionArtifacts.Count == 0 || request.TrustedFrameworkArtifacts.Count == 0)
    {
        return 4;
    }

    using var analyzer = new AssemblyAnalyzer(request);
    var response = analyzer.Analyze();
    var bytes = JsonSerializer.SerializeToUtf8Bytes(response, JsonOptions.Default.AnalyzerResponse);
    if (bytes.Length > 16 * 1024 * 1024)
    {
        return 5;
    }
    File.WriteAllBytes(responsePath, bytes);
    return 0;
}
catch
{
    return 1;
}

internal sealed record AnalyzerRequest(
    int Schema,
    string TestAssembly,
    IReadOnlyList<string> Classes,
    IReadOnlyList<string> Artifacts,
    IReadOnlyList<string> ProductionArtifacts,
    IReadOnlyList<string> TrustedFrameworkArtifacts);

internal sealed record ArtifactResult(
    string Name,
    string Path,
    string Sha256,
    IReadOnlyList<string> Dependencies);

internal sealed record AnalyzerResponse(
    int Schema,
    string TestAssemblySha256,
    IReadOnlyList<ArtifactResult> Artifacts,
    IReadOnlyDictionary<string, IReadOnlyList<string>> Classes);

internal sealed class AssemblyAnalyzer : IDisposable
{
    private static readonly IReadOnlyDictionary<short, OpCode> OpCodesByValue = typeof(OpCodes)
        .GetFields(BindingFlags.Public | BindingFlags.Static)
        .Where(field => field.FieldType == typeof(OpCode))
        .Select(field => (OpCode)field.GetValue(null)!)
        .ToDictionary(opcode => opcode.Value);

    private static readonly string[] UnsafeTypePrefixes =
    [
        "System.Activator",
        "System.AppDomain",
        "System.Environment",
        "System.IO.Directory",
        "System.IO.File",
        "System.IO.Path",
        "System.Reflection.",
        "System.Resources.",
        "System.Runtime.InteropServices.NativeLibrary",
        "System.Runtime.Loader.",
        "System.Type",
        "Microsoft.CSharp.RuntimeBinder."
    ];

    private static readonly string[] UnsafeAttributeNames =
    [
        "ClassDataAttribute",
        "CollectionAttribute",
        "CollectionBehaviorAttribute",
        "CollectionDefinitionAttribute",
        "DataSourceAttribute",
        "DataRowAttribute",
        "DataTestMethodAttribute",
        "DeploymentItemAttribute",
        "DynamicDataAttribute",
        "InlineDataAttribute",
        "MemberDataAttribute",
        "OneTimeSetUpAttribute",
        "OneTimeTearDownAttribute",
        "RangeAttribute",
        "RandomAttribute",
        "SetUpFixtureAttribute",
        "TestCaseSourceAttribute",
        "TestCaseAttribute",
        "TestFixtureSourceAttribute",
        "TheoryAttribute",
        "ValuesAttribute",
        "ValueSourceAttribute"
    ];

    private static readonly IReadOnlyDictionary<string, string> SafeFrameworkAttributes =
        new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["Xunit.FactAttribute"] = "xunit.core",
            ["Xunit.TraitAttribute"] = "xunit.core",
            ["NUnit.Framework.CategoryAttribute"] = "nunit.framework",
            ["NUnit.Framework.NonParallelizableAttribute"] = "nunit.framework",
            ["NUnit.Framework.TestAttribute"] = "nunit.framework",
            ["NUnit.Framework.TestFixtureAttribute"] = "nunit.framework",
            ["Microsoft.VisualStudio.TestTools.UnitTesting.OwnerAttribute"] = "MSTest.TestFramework",
            ["Microsoft.VisualStudio.TestTools.UnitTesting.PriorityAttribute"] = "MSTest.TestFramework",
            ["Microsoft.VisualStudio.TestTools.UnitTesting.TestCategoryAttribute"] = "MSTest.TestFramework",
            ["Microsoft.VisualStudio.TestTools.UnitTesting.TestClassAttribute"] = "MSTest.TestFramework",
            ["Microsoft.VisualStudio.TestTools.UnitTesting.TestMethodAttribute"] = "MSTest.TestFramework",
            ["Microsoft.VisualStudio.TestTools.UnitTesting.TimeoutAttribute"] = "MSTest.TestFramework"
        };

    private static readonly HashSet<string> HarmlessMetadataAttributes =
    [
        "System.CLSCompliantAttribute",
        "System.CodeDom.Compiler.GeneratedCodeAttribute",
        "System.ComponentModel.EditorBrowsableAttribute",
        "System.Diagnostics.DebuggableAttribute",
        "System.Diagnostics.DebuggerBrowsableAttribute",
        "System.Diagnostics.DebuggerDisplayAttribute",
        "System.Diagnostics.DebuggerHiddenAttribute",
        "System.Diagnostics.DebuggerStepThroughAttribute",
        "System.ObsoleteAttribute",
        "System.Reflection.AssemblyCompanyAttribute",
        "System.Reflection.AssemblyConfigurationAttribute",
        "System.Reflection.AssemblyCopyrightAttribute",
        "System.Reflection.AssemblyDescriptionAttribute",
        "System.Reflection.AssemblyFileVersionAttribute",
        "System.Reflection.AssemblyInformationalVersionAttribute",
        "System.Reflection.AssemblyProductAttribute",
        "System.Reflection.AssemblyTitleAttribute",
        "System.Reflection.AssemblyTrademarkAttribute",
        "System.Runtime.CompilerServices.AsyncStateMachineAttribute",
        "System.Runtime.CompilerServices.CallerArgumentExpressionAttribute",
        "System.Runtime.CompilerServices.CallerFilePathAttribute",
        "System.Runtime.CompilerServices.CallerLineNumberAttribute",
        "System.Runtime.CompilerServices.CallerMemberNameAttribute",
        "System.Runtime.CompilerServices.CompilationRelaxationsAttribute",
        "System.Runtime.CompilerServices.CompilerFeatureRequiredAttribute",
        "System.Runtime.CompilerServices.CompilerGeneratedAttribute",
        "System.Runtime.CompilerServices.ExtensionAttribute",
        "System.Runtime.CompilerServices.InterpolatedStringHandlerAttribute",
        "System.Runtime.CompilerServices.InterpolatedStringHandlerArgumentAttribute",
        "System.Runtime.CompilerServices.IsByRefLikeAttribute",
        "System.Runtime.CompilerServices.IsReadOnlyAttribute",
        "System.Runtime.CompilerServices.IteratorStateMachineAttribute",
        "System.Runtime.CompilerServices.NullableAttribute",
        "System.Runtime.CompilerServices.NullableContextAttribute",
        "System.Runtime.CompilerServices.RefSafetyRulesAttribute",
        "System.Runtime.CompilerServices.RequiredMemberAttribute",
        "System.Runtime.CompilerServices.RuntimeCompatibilityAttribute",
        "System.Runtime.Versioning.TargetFrameworkAttribute"
    ];

    private readonly AnalyzerRequest request;
    private readonly FileStream stream;
    private readonly PEReader pe;
    private readonly MetadataReader metadata;
    private readonly IReadOnlyDictionary<string, ArtifactResult> artifacts;
    private readonly IReadOnlyDictionary<string, ArtifactMetadata> artifactMetadata;
    private readonly IReadOnlyDictionary<string, TypeDefinitionHandle> types;
    private readonly DependencyTypeProvider typeProvider;

    internal AssemblyAnalyzer(AnalyzerRequest request)
    {
        this.request = request;
        var testAssembly = RegularFile(request.TestAssembly);
        stream = File.OpenRead(testAssembly);
        pe = new PEReader(stream, PEStreamOptions.PrefetchEntireImage);
        metadata = pe.GetMetadataReader();
        var requestedArtifacts = request.Artifacts.Select(RegularFile).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var productionArtifacts = request.ProductionArtifacts.Select(RegularFile)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        var trustedFrameworkArtifacts = request.TrustedFrameworkArtifacts.Select(RegularFile)
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        if (requestedArtifacts.Count != request.Artifacts.Count ||
            productionArtifacts.Count != request.ProductionArtifacts.Count ||
            trustedFrameworkArtifacts.Count != request.TrustedFrameworkArtifacts.Count ||
            !productionArtifacts.IsSubsetOf(requestedArtifacts) ||
            !trustedFrameworkArtifacts.IsSubsetOf(requestedArtifacts) ||
            productionArtifacts.Overlaps(trustedFrameworkArtifacts))
        {
            throw new InvalidDataException();
        }
        artifactMetadata = requestedArtifacts
            .Select(ReadArtifact)
            .Select(artifact => trustedFrameworkArtifacts.Contains(artifact.Path)
                    ? artifact with { Uncertain = false }
                    : artifact)
            .ToDictionary(artifact => artifact.Name, StringComparer.OrdinalIgnoreCase);
        if (artifactMetadata.Count != requestedArtifacts.Count)
        {
            throw new InvalidDataException();
        }
        RejectUncertainProductionGraph(artifactMetadata, productionArtifacts);
        artifacts = artifactMetadata.Values.ToDictionary(
            artifact => artifact.Name,
            artifact => new ArtifactResult(
                artifact.Name,
                artifact.Path,
                artifact.Sha256,
                artifact.References.Where(artifactMetadata.ContainsKey).Order(StringComparer.Ordinal).ToArray()),
            StringComparer.OrdinalIgnoreCase);
        types = metadata.TypeDefinitions
            .Select(handle => (Name: FullName(handle), Handle: handle))
            .ToDictionary(item => item.Name, item => item.Handle, StringComparer.Ordinal);
        typeProvider = new DependencyTypeProvider(this);
    }

    internal AnalyzerResponse Analyze()
    {
        RejectGlobalLifecycle();
        var classes = new SortedDictionary<string, IReadOnlyList<string>>(StringComparer.Ordinal);
        foreach (var name in request.Classes.Distinct(StringComparer.Ordinal).Order(StringComparer.Ordinal))
        {
            if (!types.TryGetValue(name, out var type))
            {
                throw new InvalidDataException();
            }
            var dependencies = AnalyzeType(type);
            RejectUncertainGraph(dependencies);
            classes.Add(name, dependencies.Order(StringComparer.Ordinal).ToArray());
        }
        return new AnalyzerResponse(
            3,
            Hash(RegularFile(request.TestAssembly)),
            artifacts.Values.OrderBy(artifact => artifact.Name, StringComparer.Ordinal).ToArray(),
            classes);
    }

    public void Dispose()
    {
        pe.Dispose();
        stream.Dispose();
    }

    private HashSet<string> AnalyzeType(TypeDefinitionHandle root)
    {
        var dependencies = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var methods = new Queue<MethodDefinitionHandle>();
        var seen = new HashSet<MethodDefinitionHandle>();
        var type = metadata.GetTypeDefinition(root);
        AddEntity(type.BaseType, dependencies, methods);
        foreach (var implementation in type.GetInterfaceImplementations())
        {
            AddEntity(metadata.GetInterfaceImplementation(implementation).Interface, dependencies, methods);
        }
        foreach (var attribute in type.GetCustomAttributes())
        {
            AddAttribute(attribute, dependencies, methods);
        }
        foreach (var field in type.GetFields())
        {
            dependencies.UnionWith(metadata.GetFieldDefinition(field).DecodeSignature(typeProvider, null));
        }
        foreach (var method in type.GetMethods())
        {
            methods.Enqueue(method);
        }
        while (methods.TryDequeue(out var method))
        {
            if (!seen.Add(method))
            {
                continue;
            }
            AnalyzeMethod(method, dependencies, methods);
        }
        return dependencies;
    }

    private void AnalyzeMethod(
        MethodDefinitionHandle handle,
        HashSet<string> dependencies,
        Queue<MethodDefinitionHandle> methods)
    {
        var method = metadata.GetMethodDefinition(handle);
        if ((method.Attributes & MethodAttributes.PinvokeImpl) != 0)
        {
            throw new InvalidDataException();
        }
        var signature = method.DecodeSignature(typeProvider, null);
        dependencies.UnionWith(signature.ReturnType);
        foreach (var parameter in signature.ParameterTypes)
        {
            dependencies.UnionWith(parameter);
        }
        foreach (var attribute in method.GetCustomAttributes())
        {
            AddAttribute(attribute, dependencies, methods);
        }
        foreach (var parameterHandle in method.GetParameters())
        {
            foreach (var attribute in metadata.GetParameter(parameterHandle).GetCustomAttributes())
            {
                AddAttribute(attribute, dependencies, methods);
            }
        }
        if (method.RelativeVirtualAddress == 0)
        {
            return;
        }
        var body = pe.GetMethodBody(method.RelativeVirtualAddress);
        if (!body.LocalSignature.IsNil)
        {
            var locals = metadata.GetStandaloneSignature(body.LocalSignature)
                .DecodeLocalSignature(typeProvider, null);
            foreach (var local in locals)
            {
                dependencies.UnionWith(local);
            }
        }
        foreach (var region in body.ExceptionRegions)
        {
            AddEntity(region.CatchType, dependencies, methods);
        }
        AnalyzeIl(body.GetILBytes() ?? throw new InvalidDataException(), dependencies, methods);
    }

    private void AnalyzeIl(
        byte[] bytes,
        HashSet<string> dependencies,
        Queue<MethodDefinitionHandle> methods)
    {
        var index = 0;
        while (index < bytes.Length)
        {
            short value = bytes[index++];
            if (value == 0xfe)
            {
                if (index >= bytes.Length)
                {
                    throw new InvalidDataException();
                }
                value = unchecked((short)(0xfe00 | bytes[index++]));
            }
            if (!OpCodesByValue.TryGetValue(value, out var opcode))
            {
                throw new InvalidDataException();
            }
            switch (opcode.OperandType)
            {
                case OperandType.InlineField:
                case OperandType.InlineMethod:
                case OperandType.InlineTok:
                case OperandType.InlineType:
                    AddEntity(MetadataTokens.EntityHandle(ReadInt32(bytes, ref index)), dependencies, methods);
                    break;
                case OperandType.InlineSig:
                    throw new InvalidDataException();
                case OperandType.InlineString:
                case OperandType.InlineBrTarget:
                case OperandType.InlineI:
                case OperandType.ShortInlineR:
                    Skip(bytes, ref index, 4);
                    break;
                case OperandType.InlineI8:
                case OperandType.InlineR:
                    Skip(bytes, ref index, 8);
                    break;
                case OperandType.InlineSwitch:
                    var count = ReadInt32(bytes, ref index);
                    if (count < 0 || count > 1_000_000)
                    {
                        throw new InvalidDataException();
                    }
                    Skip(bytes, ref index, checked(count * 4));
                    break;
                case OperandType.InlineVar:
                    Skip(bytes, ref index, 2);
                    break;
                case OperandType.ShortInlineBrTarget:
                case OperandType.ShortInlineI:
                case OperandType.ShortInlineVar:
                    Skip(bytes, ref index, 1);
                    break;
                case OperandType.InlineNone:
                    break;
                default:
                    throw new InvalidDataException();
            }
        }
    }

    private void AddAttribute(
        CustomAttributeHandle handle,
        HashSet<string> dependencies,
        Queue<MethodDefinitionHandle> methods)
    {
        var attribute = metadata.GetCustomAttribute(handle);
        var identity = EntityIdentity(attribute.Constructor);
        if (UnsafeAttributeNames.Any(name => identity.TypeName.EndsWith(name, StringComparison.Ordinal)))
        {
            throw new InvalidDataException();
        }
        var harmless = HarmlessMetadataAttributes.Contains(identity.TypeName);
        var framework = SafeFrameworkAttributes.TryGetValue(identity.TypeName, out var assembly) &&
            string.Equals(identity.Assembly, assembly, StringComparison.OrdinalIgnoreCase) &&
            (identity.TypeName != "NUnit.Framework.TestFixtureAttribute" || IsParameterlessAttribute(attribute));
        if (!harmless && !framework)
        {
            throw new InvalidDataException();
        }
        AddEntity(attribute.Constructor, dependencies, methods);
    }

    private bool IsParameterlessAttribute(CustomAttribute attribute) =>
        metadata.GetBlobBytes(attribute.Value).SequenceEqual(new byte[] { 1, 0, 0, 0 });

    private void AddEntity(
        EntityHandle handle,
        HashSet<string> dependencies,
        Queue<MethodDefinitionHandle> methods)
    {
        if (handle.IsNil)
        {
            return;
        }
        switch (handle.Kind)
        {
            case HandleKind.MethodDefinition:
                var method = (MethodDefinitionHandle)handle;
                methods.Enqueue(method);
                EnqueueStaticConstructor(metadata.GetMethodDefinition(method).GetDeclaringType(), methods);
                break;
            case HandleKind.MemberReference:
                var member = metadata.GetMemberReference((MemberReferenceHandle)handle);
                var identity = EntityIdentity(member.Parent);
                RejectUnsafe(identity);
                AddAssembly(identity.Assembly, dependencies);
                AddEntity(member.Parent, dependencies, methods);
                break;
            case HandleKind.MethodSpecification:
                var specification = metadata.GetMethodSpecification((MethodSpecificationHandle)handle);
                AddEntity(specification.Method, dependencies, methods);
                foreach (var argument in specification.DecodeSignature(typeProvider, null))
                {
                    dependencies.UnionWith(argument);
                }
                break;
            case HandleKind.TypeReference:
                var type = TypeIdentity((TypeReferenceHandle)handle);
                RejectUnsafe(type);
                AddAssembly(type.Assembly, dependencies);
                break;
            case HandleKind.TypeSpecification:
                dependencies.UnionWith(
                    metadata.GetTypeSpecification((TypeSpecificationHandle)handle).DecodeSignature(typeProvider, null));
                break;
            case HandleKind.TypeDefinition:
                throw new InvalidDataException();
            case HandleKind.FieldDefinition:
                var field = metadata.GetFieldDefinition((FieldDefinitionHandle)handle);
                dependencies.UnionWith(field.DecodeSignature(typeProvider, null));
                EnqueueStaticConstructor(field.GetDeclaringType(), methods);
                break;
            default:
                throw new InvalidDataException();
        }
    }

    private void EnqueueStaticConstructor(
        TypeDefinitionHandle declaringType,
        Queue<MethodDefinitionHandle> methods)
    {
        foreach (var method in metadata.GetTypeDefinition(declaringType).GetMethods())
        {
            if (metadata.GetString(metadata.GetMethodDefinition(method).Name) == ".cctor")
            {
                methods.Enqueue(method);
            }
        }
    }

    private EntityName EntityIdentity(EntityHandle handle) => handle.Kind switch
    {
        HandleKind.MemberReference => EntityIdentity(metadata.GetMemberReference((MemberReferenceHandle)handle).Parent),
        HandleKind.MethodDefinition => TypeIdentity(metadata.GetMethodDefinition((MethodDefinitionHandle)handle).GetDeclaringType()),
        HandleKind.TypeDefinition => TypeIdentity((TypeDefinitionHandle)handle),
        HandleKind.TypeReference => TypeIdentity((TypeReferenceHandle)handle),
        _ => throw new InvalidDataException()
    };

    private EntityName TypeIdentity(TypeDefinitionHandle handle) => new(null, FullName(handle));

    private EntityName TypeIdentity(TypeReferenceHandle handle)
    {
        var type = metadata.GetTypeReference(handle);
        var typeName = metadata.GetString(type.Name);
        var typeNamespace = metadata.GetString(type.Namespace);
        return type.ResolutionScope.Kind switch
        {
            HandleKind.AssemblyReference => new(
                metadata.GetString(metadata.GetAssemblyReference((AssemblyReferenceHandle)type.ResolutionScope).Name),
                JoinName(typeNamespace, typeName)),
            HandleKind.TypeReference => TypeIdentity((TypeReferenceHandle)type.ResolutionScope) with
            {
                TypeName = $"{TypeIdentity((TypeReferenceHandle)type.ResolutionScope).TypeName}+{typeName}"
            },
            HandleKind.ModuleDefinition => new(null, JoinName(typeNamespace, typeName)),
            _ => throw new InvalidDataException()
        };
    }

    private string FullName(TypeDefinitionHandle handle)
    {
        var type = metadata.GetTypeDefinition(handle);
        var name = metadata.GetString(type.Name);
        if (!type.GetDeclaringType().IsNil)
        {
            return $"{FullName(type.GetDeclaringType())}+{name}";
        }
        return JoinName(metadata.GetString(type.Namespace), name);
    }

    private void RejectUnsafe(EntityName identity)
    {
        if (UnsafeTypePrefixes.Any(prefix => identity.TypeName.StartsWith(prefix, StringComparison.Ordinal)))
        {
            throw new InvalidDataException();
        }
    }

    private void AddAssembly(string? name, HashSet<string> dependencies)
    {
        if (name is not null && artifacts.ContainsKey(name))
        {
            dependencies.Add(name);
        }
    }

    private void RejectGlobalLifecycle()
    {
        foreach (var attribute in metadata.GetAssemblyDefinition().GetCustomAttributes())
        {
            var identity = EntityIdentity(metadata.GetCustomAttribute(attribute).Constructor);
            if (!HarmlessMetadataAttributes.Contains(identity.TypeName))
            {
                throw new InvalidDataException();
            }
        }
        foreach (var handle in metadata.TypeDefinitions)
        {
            var type = metadata.GetTypeDefinition(handle);
            foreach (var attribute in type.GetCustomAttributes())
            {
                RejectGlobalAttribute(attribute);
            }
            foreach (var methodHandle in type.GetMethods())
            {
                foreach (var attribute in metadata.GetMethodDefinition(methodHandle).GetCustomAttributes())
                {
                    RejectGlobalAttribute(attribute);
                }
            }
        }
    }

    private void RejectGlobalAttribute(CustomAttributeHandle handle)
    {
        var attribute = metadata.GetCustomAttribute(handle);
        var identity = EntityIdentity(attribute.Constructor);
        if (UnsafeAttributeNames.Any(name => identity.TypeName.EndsWith(name, StringComparison.Ordinal)) ||
            identity.TypeName.EndsWith("AssemblyInitializeAttribute", StringComparison.Ordinal) ||
            identity.TypeName.EndsWith("AssemblyCleanupAttribute", StringComparison.Ordinal) ||
            identity.TypeName.EndsWith("ModuleInitializerAttribute", StringComparison.Ordinal))
        {
            throw new InvalidDataException();
        }
    }

    private ArtifactMetadata ReadArtifact(string path)
    {
        var file = RegularFile(path);
        using var artifactStream = File.OpenRead(file);
        using var artifactPe = new PEReader(artifactStream, PEStreamOptions.PrefetchEntireImage);
        var reader = artifactPe.GetMetadataReader();
        var name = reader.GetString(reader.GetAssemblyDefinition().Name);
        var references = reader.AssemblyReferences
            .Select(handle => reader.GetString(reader.GetAssemblyReference(handle).Name))
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        return new ArtifactMetadata(name, file, Hash(file), references, HasUncertainCode(artifactPe, reader));
    }

    private static bool HasUncertainCode(PEReader pe, MetadataReader reader)
    {
        for (var row = 1; row <= reader.GetTableRowCount(TableIndex.CustomAttribute); row++)
        {
            var attribute = reader.GetCustomAttribute(MetadataTokens.CustomAttributeHandle(row));
            if (!HarmlessMetadataAttributes.Contains(ProductionAttributeTypeName(reader, attribute.Constructor)))
            {
                return true;
            }
        }
        foreach (var handle in reader.MethodDefinitions)
        {
            var method = reader.GetMethodDefinition(handle);
            if ((method.Attributes & MethodAttributes.PinvokeImpl) != 0)
            {
                return true;
            }
            if (method.RelativeVirtualAddress != 0 && ProductionMethodIsUncertain(pe, reader, method))
            {
                return true;
            }
        }
        return false;
    }

    private static string ProductionAttributeTypeName(MetadataReader reader, EntityHandle constructor)
    {
        var type = constructor.Kind switch
        {
            HandleKind.MethodDefinition =>
                (EntityHandle)reader.GetMethodDefinition((MethodDefinitionHandle)constructor).GetDeclaringType(),
            HandleKind.MemberReference => reader.GetMemberReference((MemberReferenceHandle)constructor).Parent,
            _ => default
        };
        return type.IsNil ? string.Empty : ProductionTypeName(reader, type);
    }

    private static bool ProductionMethodIsUncertain(
        PEReader pe,
        MetadataReader reader,
        MethodDefinition method)
    {
        var bytes = pe.GetMethodBody(method.RelativeVirtualAddress).GetILBytes() ?? throw new InvalidDataException();
        var index = 0;
        while (index < bytes.Length)
        {
            short value = bytes[index++];
            if (value == 0xfe)
            {
                if (index >= bytes.Length) throw new InvalidDataException();
                value = unchecked((short)(0xfe00 | bytes[index++]));
            }
            if (!OpCodesByValue.TryGetValue(value, out var opcode)) throw new InvalidDataException();
            switch (opcode.OperandType)
            {
                case OperandType.InlineField:
                case OperandType.InlineMethod:
                case OperandType.InlineTok:
                case OperandType.InlineType:
                    var entity = MetadataTokens.EntityHandle(ReadInt32(bytes, ref index));
                    if (ProductionEntityIsUncertain(reader, entity)) return true;
                    break;
                case OperandType.InlineSig:
                    return true;
                case OperandType.InlineString:
                case OperandType.InlineBrTarget:
                case OperandType.InlineI:
                case OperandType.ShortInlineR:
                    Skip(bytes, ref index, 4);
                    break;
                case OperandType.InlineI8:
                case OperandType.InlineR:
                    Skip(bytes, ref index, 8);
                    break;
                case OperandType.InlineSwitch:
                    var count = ReadInt32(bytes, ref index);
                    if (count < 0 || count > 1_000_000) throw new InvalidDataException();
                    Skip(bytes, ref index, checked(count * 4));
                    break;
                case OperandType.InlineVar:
                    Skip(bytes, ref index, 2);
                    break;
                case OperandType.ShortInlineBrTarget:
                case OperandType.ShortInlineI:
                case OperandType.ShortInlineVar:
                    Skip(bytes, ref index, 1);
                    break;
                case OperandType.InlineNone:
                    break;
                default:
                    throw new InvalidDataException();
            }
        }
        return false;
    }

    private static bool ProductionEntityIsUncertain(MetadataReader reader, EntityHandle handle)
    {
        if (handle.Kind == HandleKind.MethodSpecification)
        {
            return ProductionEntityIsUncertain(
                reader,
                reader.GetMethodSpecification((MethodSpecificationHandle)handle).Method);
        }
        if (handle.Kind != HandleKind.MemberReference && handle.Kind != HandleKind.TypeReference)
        {
            return false;
        }
        var type = handle.Kind == HandleKind.MemberReference
            ? reader.GetMemberReference((MemberReferenceHandle)handle).Parent
            : handle;
        var name = ProductionTypeName(reader, type);
        return UnsafeTypePrefixes.Any(prefix => name.StartsWith(prefix, StringComparison.Ordinal));
    }

    private static string ProductionTypeName(MetadataReader reader, EntityHandle handle)
    {
        if (handle.Kind == HandleKind.TypeSpecification) return string.Empty;
        if (handle.Kind == HandleKind.TypeDefinition)
        {
            var type = reader.GetTypeDefinition((TypeDefinitionHandle)handle);
            return JoinName(reader.GetString(type.Namespace), reader.GetString(type.Name));
        }
        if (handle.Kind != HandleKind.TypeReference) return string.Empty;
        var reference = reader.GetTypeReference((TypeReferenceHandle)handle);
        var name = JoinName(reader.GetString(reference.Namespace), reader.GetString(reference.Name));
        return reference.ResolutionScope.Kind == HandleKind.TypeReference
            ? $"{ProductionTypeName(reader, reference.ResolutionScope)}+{name}"
            : name;
    }

    private static void RejectUncertainProductionGraph(
        IReadOnlyDictionary<string, ArtifactMetadata> artifacts,
        IReadOnlySet<string> productionPaths)
    {
        var pending = new Queue<string>(artifacts.Values
            .Where(artifact => productionPaths.Contains(artifact.Path))
            .Select(artifact => artifact.Name));
        if (pending.Count != productionPaths.Count)
        {
            throw new InvalidDataException();
        }
        var reached = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        while (pending.TryDequeue(out var name))
        {
            if (!reached.Add(name))
            {
                continue;
            }
            var artifact = artifacts[name];
            if (artifact.Uncertain)
            {
                throw new InvalidDataException();
            }
            foreach (var dependency in artifact.References.Where(artifacts.ContainsKey))
            {
                pending.Enqueue(dependency);
            }
        }
    }

    private void RejectUncertainGraph(IEnumerable<string> seeds)
    {
        var pending = new Queue<string>(seeds.Where(artifactMetadata.ContainsKey));
        var reached = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        while (pending.TryDequeue(out var name))
        {
            if (!reached.Add(name))
            {
                continue;
            }
            var artifact = artifactMetadata[name];
            if (artifact.Uncertain)
            {
                throw new InvalidDataException();
            }
            foreach (var dependency in artifact.References.Where(artifactMetadata.ContainsKey))
            {
                pending.Enqueue(dependency);
            }
        }
    }

    private static string RegularFile(string path)
    {
        var full = Path.GetFullPath(path);
        var info = new FileInfo(full);
        if (!info.Exists || info.LinkTarget is not null || info.Length > 256L * 1024 * 1024)
        {
            throw new InvalidDataException();
        }
        return full;
    }

    private static string Hash(string path)
    {
        using var input = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(input)).ToLowerInvariant();
    }

    private static int ReadInt32(byte[] bytes, ref int index)
    {
        if (index > bytes.Length - 4)
        {
            throw new InvalidDataException();
        }
        var value = bytes[index] |
            bytes[index + 1] << 8 |
            bytes[index + 2] << 16 |
            bytes[index + 3] << 24;
        index += 4;
        return value;
    }

    private static void Skip(byte[] bytes, ref int index, int count)
    {
        if (count < 0 || index > bytes.Length - count)
        {
            throw new InvalidDataException();
        }
        index += count;
    }

    private static string JoinName(string typeNamespace, string name) =>
        string.IsNullOrEmpty(typeNamespace) ? name : $"{typeNamespace}.{name}";

    private sealed class DependencyTypeProvider : ISignatureTypeProvider<HashSet<string>, object?>
    {
        private readonly AssemblyAnalyzer analyzer;

        internal DependencyTypeProvider(AssemblyAnalyzer analyzer)
        {
            this.analyzer = analyzer;
        }

        public HashSet<string> GetArrayType(HashSet<string> elementType, ArrayShape shape) => elementType;
        public HashSet<string> GetByReferenceType(HashSet<string> elementType) => elementType;
        public HashSet<string> GetFunctionPointerType(MethodSignature<HashSet<string>> signature) =>
            Combine(signature.ReturnType, signature.ParameterTypes);
        public HashSet<string> GetGenericInstantiation(
            HashSet<string> genericType,
            ImmutableArray<HashSet<string>> typeArguments) => Combine(genericType, typeArguments);
        public HashSet<string> GetGenericMethodParameter(object? genericContext, int index) => New();
        public HashSet<string> GetGenericTypeParameter(object? genericContext, int index) => New();
        public HashSet<string> GetModifiedType(
            HashSet<string> modifier,
            HashSet<string> unmodifiedType,
            bool isRequired) => Combine(modifier, [unmodifiedType]);
        public HashSet<string> GetPinnedType(HashSet<string> elementType) => elementType;
        public HashSet<string> GetPointerType(HashSet<string> elementType) => elementType;
        public HashSet<string> GetPrimitiveType(PrimitiveTypeCode typeCode) => New();
        public HashSet<string> GetSZArrayType(HashSet<string> elementType) => elementType;
        public HashSet<string> GetTypeFromDefinition(
            MetadataReader reader,
            TypeDefinitionHandle handle,
            byte rawTypeKind) => throw new InvalidDataException();
        public HashSet<string> GetTypeFromReference(
            MetadataReader reader,
            TypeReferenceHandle handle,
            byte rawTypeKind)
        {
            var identity = analyzer.TypeIdentity(handle);
            analyzer.RejectUnsafe(identity);
            var result = New();
            analyzer.AddAssembly(identity.Assembly, result);
            return result;
        }
        public HashSet<string> GetTypeFromSpecification(
            MetadataReader reader,
            object? genericContext,
            TypeSpecificationHandle handle,
            byte rawTypeKind) => reader.GetTypeSpecification(handle).DecodeSignature(this, genericContext);

        private static HashSet<string> Combine(
            HashSet<string> first,
            IEnumerable<HashSet<string>> rest)
        {
            var result = New();
            result.UnionWith(first);
            foreach (var item in rest)
            {
                result.UnionWith(item);
            }
            return result;
        }

        private static HashSet<string> New() => new(StringComparer.OrdinalIgnoreCase);
    }

    private sealed record EntityName(string? Assembly, string TypeName);
    private sealed record ArtifactMetadata(
        string Name,
        string Path,
        string Sha256,
        IReadOnlySet<string> References,
        bool Uncertain);
}

[JsonSourceGenerationOptions(PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(AnalyzerRequest))]
[JsonSerializable(typeof(AnalyzerResponse))]
internal sealed partial class JsonOptions : JsonSerializerContext;
