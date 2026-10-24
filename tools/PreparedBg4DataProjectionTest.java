package com.winlator.teknoparrot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Real temporary filesystem and atomic symlink operations; no Android/device. */
public final class PreparedBg4DataProjectionTest {
    private static int checks, sequence;
    private static final String LOADER = "E:\\TeknoParrotRuntime\\OpenParrotWin32\\OpenParrotLoader.exe";
    private interface Action { void run() throws Exception; }
    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
    private static void reject(Action action, String reason) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException expected) { rejected = true; }
        check(rejected, reason);
    }
    private static final class Fixture {
        final Path base, root, home, prefix, storage, runtime, game, data, movie, e;
        final List<PreparedBg4DataProjection.Mount> mounts = new ArrayList<>();
        final AtomicBoolean stopped = new AtomicBoolean(true);
        final String operation = "00000000-0000-0000-0000-" + String.format("%012d", ++sequence);
        final PreparedBg4DataProjection.FileSystem fs = new PreparedBg4DataProjection.FileSystem() {
            // Windows cannot name a file e:. Mirror only that filename; all
            // policy, actual symlink, containment and atomic-move code is real.
            @Override Path driveLink(Path prefix) { return prefix.resolve("dosdevices/e-drive"); }
            @Override void replace(Path source, Path target) throws IOException {
                if (System.getProperty("os.name").startsWith("Windows")) {
                    // NTFS cannot atomically replace a directory symlink with
                    // Java's move API. Emulate that one POSIX operation in this
                    // owned test fixture only. Production never unlinks E.
                    if (!Files.isSymbolicLink(target)) throw new IOException("fixture target changed");
                    Files.delete(target);
                    Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);
                }
                else super.replace(source,target);
            }
        };
        Fixture(Path output) throws Exception {
            base = output.resolve("case-" + sequence + "-" + System.nanoTime());
            root = base.resolve("rootfs"); home = root.resolve("home/xuser-1");
            prefix = home.resolve(".wine"); storage = base.resolve("private/storage");
            runtime = storage.resolve("TeknoParrotRuntime"); game = base.resolve("shared/BG4/game.exe");
            data = game.getParent().resolve("data"); movie = data.resolve("Scene/demo/attract/attract_demo.wmv");
            Files.createDirectories(prefix.resolve("dosdevices"));
            Files.createDirectories(runtime.resolve("OpenParrotWin32"));
            Files.createDirectories(runtime.resolve(".packages"));
            Files.write(runtime.resolve("OpenParrotWin32/OpenParrot.dll"), new byte[]{1,2,3});
            Files.write(runtime.resolve(".packages/public-marker.json"), new byte[]{9,8,7});
            Files.createDirectories(movie.getParent()); Files.write(movie,new byte[]{4,5,6});
            Files.write(game,new byte[]{7,8,9});
            e = fs.driveLink(prefix);
            Files.createSymbolicLink(e,storage.toAbsolutePath());
            for (String letter : Arrays.asList("D","G","H","I")) {
                Path folder = base.resolve("mount-"+letter); Files.createDirectory(folder);
                mounts.add(new PreparedBg4DataProjection.Mount(letter,folder.toFile()));
                Files.createSymbolicLink(prefix.resolve("dosdevices/"+letter.toLowerCase()+"-drive"), folder.toAbsolutePath());
            }
            mounts.add(new PreparedBg4DataProjection.Mount("E",storage.toFile()));
        }
        PreparedBg4DataProjection.Session prepare() throws IOException {
            return PreparedBg4DataProjection.prepare(root.toFile(),home.toFile(),1,true,
                storage.toFile(),game.toFile(),mounts,stopped::get,fs,operation);
        }
        Path owned() { return prefix.resolve(".teknoparrot-bg4-projections/"+operation); }
        void original() throws Exception {
            check(e.toRealPath().equals(storage.toRealPath()),"E points to original storage");
            check(Arrays.equals(Files.readAllBytes(runtime.resolve("OpenParrotWin32/OpenParrot.dll")),new byte[]{1,2,3}),"runtime bytes preserved");
            check(Arrays.equals(Files.readAllBytes(runtime.resolve(".packages/public-marker.json")),new byte[]{9,8,7}),"marker preserved");
        }
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output);
        for (char drive : new char[]{'D','G','H','I'})
            check(PreparedBg4DataProjection.isEligible(true,"battle-gear-4-original",9008,LOADER,drive+":\\BG4\\game.exe"),"exact mapped route");
        check(!PreparedBg4DataProjection.isEligible(false,"battle-gear-4-original",9008,LOADER,"G:\\BG4\\game.exe"),"diagnostic bridge excluded");
        for (String preset : new String[]{null,"","classic-640-client","battle-gear-4-tuned"})
            check(!PreparedBg4DataProjection.isEligible(true,preset,9008,LOADER,"G:\\BG4\\game.exe"),"other presets excluded");
        for (String game : new String[]{null,"E:\\BG4\\game.exe","C:\\game.exe","G:\\BG4\\..\\game.exe","G:/BG4/game.exe","G:\\BG4\\other.exe"})
            check(!PreparedBg4DataProjection.isEligible(true,"battle-gear-4-original",9008,LOADER,game),"unsafe/unrelated game excluded");
        check(!PreparedBg4DataProjection.isEligible(true,"battle-gear-4-original",9007,LOADER,"G:\\BG4\\game.exe"),"wrong layout excluded");
        check(!PreparedBg4DataProjection.isEligible(true,"battle-gear-4-original",9008,"D:\\OpenParrotWin32\\OpenParrotLoader.exe","G:\\BG4\\game.exe"),"unmanaged loader excluded");

        Fixture f = new Fixture(output);
        List<Path> otherTargets = new ArrayList<>();
        for (String letter : Arrays.asList("d","g","h","i")) otherTargets.add(Files.readSymbolicLink(f.prefix.resolve("dosdevices/"+letter+"-drive")));
        PreparedBg4DataProjection.Session session = f.prepare();
        check(Files.isRegularFile(f.owned().resolve("owner.json")),"exclusive owner receipt exists");
        f.original(); session.activate();
        check(f.e.toRealPath().equals(f.owned().resolve("e").toRealPath()),"prefix E projected");
        check(f.e.resolve("TeknoParrotRuntime").toRealPath().equals(f.runtime.toRealPath()),"host/guest canonical runtime identical");
        check(f.e.resolve("data/Scene/demo/attract/attract_demo.wmv").toRealPath().equals(f.movie.toRealPath()),"absolute media alias exact");
        check(!Files.exists(f.storage.resolve("data")),"no global storage/data created");
        Path save = f.e.resolve("unknown-game-save.bin"); Files.write(save,new byte[]{42});
        f.stopped.set(false); reject(session::restore,"live guests prevent restore");
        check(Files.exists(save),"unknown write retained during failed restore");
        f.stopped.set(true); session.restore(); session.restore(); f.original();
        check(Files.exists(f.owned().resolve("e/unknown-game-save.bin")),"unknown game saves retained after restore");
        check(Files.exists(f.owned().resolve("owner.json")) && Files.exists(f.owned().resolve("published.json")) && Files.exists(f.owned().resolve("restored.json")),"all receipts retained");
        int index=0; for (String letter : Arrays.asList("d","g","h","i"))
            check(Files.readSymbolicLink(f.prefix.resolve("dosdevices/"+letter+"-drive")).equals(otherTargets.get(index++)),"other drives unchanged");

        Fixture duplicate = new Fixture(output); duplicate.mounts.add(new PreparedBg4DataProjection.Mount("e",duplicate.storage.toFile()));
        reject(duplicate::prepare,"duplicate E rejected"); duplicate.original();
        Fixture noE = new Fixture(output); noE.mounts.removeIf(m->m.letter.equals("E")); reject(noE::prepare,"missing E rejected"); noE.original();
        Fixture live = new Fixture(output); live.stopped.set(false); reject(live::prepare,"live guests prevent prepare"); live.original();
        check(!Files.exists(live.owned()),"no namespace write before stopped guard");
        Fixture unmanaged = new Fixture(output);
        reject(()->PreparedBg4DataProjection.prepare(unmanaged.root.toFile(),unmanaged.home.toFile(),1,false,
            unmanaged.storage.toFile(),unmanaged.game.toFile(),unmanaged.mounts,()->true,unmanaged.fs,unmanaged.operation),"unmanaged container rejected"); unmanaged.original();
        reject(()->PreparedBg4DataProjection.prepare(unmanaged.root.toFile(),unmanaged.home.toFile(),2,true,
            unmanaged.storage.toFile(),unmanaged.game.toFile(),unmanaged.mounts,()->true,unmanaged.fs,unmanaged.operation),"wrong prefix identity rejected");
        Fixture escaped = new Fixture(output); Path oldData=escaped.game.getParent().resolve("old-data");
        Files.move(escaped.data,oldData); Files.createSymbolicLink(escaped.data,oldData.getParent().getParent());
        reject(escaped::prepare,"escaped data symlink rejected"); escaped.original();
        Fixture escapedMovie = new Fixture(output); Files.delete(escapedMovie.movie); Files.createSymbolicLink(escapedMovie.movie,escapedMovie.game);
        reject(escapedMovie::prepare,"escaped movie rejected"); escapedMovie.original();
        Fixture badE = new Fixture(output); Files.delete(badE.e); Files.createDirectory(badE.e);
        reject(badE::prepare,"non-symlink E rejected"); check(Files.isDirectory(badE.e),"unknown E directory retained");
        Fixture changed = new Fixture(output); PreparedBg4DataProjection.Session changedSession = changed.prepare();
        Files.delete(changed.e); Files.createSymbolicLink(changed.e,changed.game.getParent());
        reject(changedSession::activate,"changed E before publish rejected");
        check(changed.e.toRealPath().equals(changed.game.getParent().toRealPath()),"changed E not overwritten");
        Fixture busy = new Fixture(output); PreparedBg4DataProjection.Session busySession = busy.prepare(); busy.stopped.set(false);
        reject(busySession::activate,"guests resumed before activation rejected"); busy.original();
        Fixture changedAfter = new Fixture(output); PreparedBg4DataProjection.Session active = changedAfter.prepare(); active.activate();
        Files.delete(changedAfter.e); Files.createSymbolicLink(changedAfter.e,changedAfter.game.getParent());
        reject(active::restore,"changed active E not restored over");
        check(changedAfter.e.toRealPath().equals(changedAfter.game.getParent().toRealPath()),"unknown replacement retained");
        Fixture collision = new Fixture(output); Files.createDirectories(collision.owned());
        Files.write(collision.owned().resolve("unknown-save"),new byte[]{99});
        reject(collision::prepare,"owned UUID collision rejected");
        check(Files.readAllBytes(collision.owned().resolve("unknown-save"))[0]==99,"collision data retained"); collision.original();
        Fixture lateGuest = new Fixture(output); AtomicInteger calls=new AtomicInteger();
        PreparedBg4DataProjection.Session late = PreparedBg4DataProjection.prepare(lateGuest.root.toFile(),lateGuest.home.toFile(),1,true,
            lateGuest.storage.toFile(),lateGuest.game.toFile(),lateGuest.mounts,()->calls.incrementAndGet()<3,lateGuest.fs,lateGuest.operation);
        reject(late::activate,"fresh guest guard before atomic publish"); lateGuest.original();
        Fixture atomicFailure = new Fixture(output);
        PreparedBg4DataProjection.FileSystem failedFs = new PreparedBg4DataProjection.FileSystem() {
            @Override Path driveLink(Path prefix) {return prefix.resolve("dosdevices/e-drive");}
            @Override void replace(Path source,Path target) throws IOException {throw new IOException("fixture atomic failure");}
        };
        PreparedBg4DataProjection.Session failed = PreparedBg4DataProjection.prepare(atomicFailure.root.toFile(),atomicFailure.home.toFile(),1,true,
            atomicFailure.storage.toFile(),atomicFailure.game.toFile(),atomicFailure.mounts,()->true,failedFs,atomicFailure.operation);
        reject(failed::activate,"atomic publication failure safe"); failed.restore(); atomicFailure.original();
        check(Files.exists(atomicFailure.owned().resolve("owner.json")),"failed operation evidence retained");
        System.out.println("Prepared BG4 projection: "+checks+" assertions PASS; real-filesystem fixtures retained; Windows mirrors e: filename and emulates POSIX symlink replacement; no device/atomic-publication qualification");
    }
}
