import Hero from '../assets/trytry.png';

const HeroSection = ({handleClick}) => {
    return (
        <div className="w-full">
            <div className="flex flex-col-reverse md:flex-row items-center justify-between min-h-[70vh] bg-slate-800 text-slate-100 rounded-xl shadow-lg p-6">
                <div className="w-full md:w-1/2 flex flex-col gap-6 items-center md:items-start text-center md:text-left">
                    <h1 className="text-3xl sm:text-4xl font-bold">
                        Collaborate on Code in Real Time
                    </h1>
                    <p className="text-lg sm:text-xl text-slate-300">
                        Multi-file editing, real-time sync, and secure code execution.
                    </p>
                    <button
                        onClick={handleClick}
                        className="bg-cyan-500 hover:bg-cyan-600 text-slate-900 font-semibold py-3 px-6 rounded-md shadow transition"
                    >
                        Start Now
                    </button>
                </div>
                <div className="w-full md:w-1/2 p-4">
                    <img
                        src={Hero}
                        alt="Code collaboration"
                        className="w-full h-auto object-contain rounded-md"
                    />
                </div>
            </div>
        </div>
    )
}
export default HeroSection;