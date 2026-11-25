package com.stratigraphic.mapping.simulation;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class CalculationInputPublication {
    private CalculationInputPublication() { }

    static InputPreparation.Prepared publish(Path root,Path target,ModelInputs inputs)throws IOException {
        Path base=root.toAbsolutePath().normalize(),destination=target.toAbsolutePath().normalize();
        if(!destination.startsWith(base)||destination.equals(base)||destination.startsWith(base.resolve("input_backups")))
            throw new IOException("计算输入目录必须位于工作目录内，且不能使用备份目录："+destination);
        if(Files.isSymbolicLink(destination))throw new IOException("计算输入目录不能是符号链接："+destination);
        InputPreparation.verifySnapshot(inputs);
        Files.createDirectories(destination.getParent());
        Path staged=Files.createTempDirectory(destination.getParent(),".ngi-inputs-");
        Path backup=null;
        try {
            inputs.copySnapshotTo(staged);
            int updated=0;
            for(var card:inputs.cards()) {
                Path previous=destination.resolve(card.name());
                if(!Files.isRegularFile(previous)||Files.mismatch(previous,card.snapshot())!=-1)updated++;
            }
            if(Files.isDirectory(destination))try(var children=Files.list(destination)) {
                for(Path child:children.toList())if(!managed(child.getFileName().toString()))copyTree(child,staged.resolve(child.getFileName()));
            }
            InputPreparation.verifySnapshot(ModelInputs.restoreSnapshot(staged));
            if(Files.exists(destination)) {
                Path backups=Files.createDirectories(base.resolve("input_backups"));
                backup=backups.resolve("NGI_"+LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))+"_"+UUID.randomUUID().toString().substring(0,8));
                move(destination,backup);
            }
            try {
                move(staged,destination);
            }catch(IOException error) {
                if(backup!=null)try {move(backup,destination);}catch(IOException restore){error.addSuppressed(restore);}
                throw new IOException("保存本次最终计算输入失败，已尝试恢复原目录；备份路径："+backup,error);
            }
            return new InputPreparation.Prepared(inputs,updated,backup);
        }finally {
            if(!staged.getParent().equals(destination.getParent())||!staged.getFileName().toString().startsWith(".ngi-inputs-"))
                throw new IOException("暂存目录不在计算输入目录的父目录内，停止清理："+staged);
            if(Files.exists(staged))try(var paths=Files.walk(staged)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
            }
        }
    }

    private static boolean managed(String name) {
        String lower=name.toLowerCase(Locale.ROOT);
        return lower.matches("card_.*\\.txt")||Set.of("inputs.properties","conversion.properties","sampling-original","conversion-source").contains(lower);
    }

    private static void copyTree(Path source,Path destination)throws IOException {
        try(var paths=Files.walk(source)) {
            for(Path path:paths.toList()) {
                Path target=destination.resolve(source.relativize(path));
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectories(target);
                else Files.copy(path,target,LinkOption.NOFOLLOW_LINKS);
            }
        }
    }

    private static void move(Path source,Path destination)throws IOException {
        try {Files.move(source,destination,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException error){Files.move(source,destination);}
    }
}
